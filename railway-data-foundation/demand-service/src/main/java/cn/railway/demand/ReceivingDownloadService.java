package cn.railway.demand;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Bounded receiver-side file preparation. Never forwards messages to Foundation. */
@Service
public class ReceivingDownloadService {
    public static final int MAX_ROWS=10000;
    public static final long MAX_BYTES=50L*1024*1024;
    private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate snapshot;
    private final Semaphore slots=new Semaphore(1);
    public ReceivingDownloadService(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager){this.db=db;this.json=json;snapshot=new TransactionTemplate(manager);snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public static final class Download implements AutoCloseable {
        final Path file;final String name,type;final long bytes,count;private final Semaphore slot;
        Download(Path file,String name,String type,long bytes,long count,Semaphore slot){this.file=file;this.name=name;this.type=type;this.bytes=bytes;this.count=count;this.slot=slot;}
        private boolean closed;public synchronized void close(){if(closed)return;closed=true;remove(file);slot.release();}
    }
    private interface Prepare{Download run(Path file)throws Exception;}
    private Download prepare(Prepare operation){
        if(!slots.tryAcquire())throw new ReceiverFailure(429,"当前下载正在准备，请稍后再试");Path file=null;
        try{file=Files.createTempFile("railway-demand-download-",".part");return operation.run(file);}
        catch(Exception e){remove(file);slots.release();throw failure(e,"下载文件准备失败，请重试");}
    }
    private static void remove(Path file){if(file==null)return;try{Files.deleteIfExists(file);}catch(IOException e){file.toFile().deleteOnExit();}}
    private void owner(String task,String subject){if(db.queryForObject("SELECT COUNT(*) FROM receiving_task WHERE task_id=? AND subject_id=?",Integer.class,task,subject)!=1)throw new ReceiverFailure(403,"无权下载该任务的数据");}
    public Download single(String task,String subject,long message,String format){
        owner(task,subject);
        if(!Set.of("content","record").contains(format))throw new ReceiverFailure(400,"下载格式不合法");
        return prepare(file->{
            var rows=db.queryForList("SELECT m.* FROM received_message m JOIN receiving_task t ON t.task_id=m.task_id WHERE m.task_id=? AND m.message_id=? AND t.subject_id=?",task,message,subject);
            if(rows.isEmpty())throw new ReceiverFailure(404,"消息不存在");var row=rows.get(0);String suffix,type;
            try(var out=new LimitedOutput(Files.newOutputStream(file),MAX_BYTES)){
                if(format.equals("record")){json.writeValue(out,exportRecord(row));suffix="json";type="application/json;charset=UTF-8";}
                else{
                    byte[] value=(byte[])row.get("message_value");if(value==null)throw new ReceiverFailure(400,"空值消息没有内容，请下载完整消息");
                    String kind=kind(value);suffix=kind;type=kind.equals("json")?"application/json;charset=UTF-8":kind.equals("txt")?"text/plain;charset=UTF-8":"application/octet-stream";out.write(value);
                }
            }
            return new Download(file,"receiver-message-"+message+"-"+System.currentTimeMillis()+"."+suffix,type,Files.size(file),1,slots);
        });
    }
    public Download export(String task,String subject,String topic,Integer partition,String from,String until){
        owner(task,subject);var filter=filter(task,subject,topic,partition,from,until);
        return prepare(file->{
            long started=System.nanoTime();long[] count={0};
            snapshot.executeWithoutResult(status->{
                // COUNT and row iteration share one snapshot; new arrivals cannot enlarge this file.
                var totals=db.query(connection->{var ps=connection.prepareStatement("SELECT COUNT(*) count,MAX(message_id) watermark,COALESCE(SUM(raw_bytes),0) raw_bytes FROM (SELECT message_id,COALESCE(OCTET_LENGTH(message_key),0)+COALESCE(OCTET_LENGTH(message_value),0)+OCTET_LENGTH(CAST(headers_json AS CHAR)) raw_bytes FROM received_message WHERE "+filter.where()+" ORDER BY message_id ASC LIMIT "+(MAX_ROWS+1)+") bounded");for(int i=0;i<filter.args().size();i++)ps.setObject(i+1,filter.args().get(i));ps.setQueryTimeout(15);return ps;},rs->{rs.next();var values=new HashMap<String,Object>();values.put("count",rs.getLong("count"));values.put("watermark",rs.getObject("watermark"));values.put("raw_bytes",rs.getLong("raw_bytes"));return values;});
                long rows=((Number)totals.get("count")).longValue();
                if(rows==0)throw new ReceiverFailure(400,"没有符合筛选条件的已落库消息");
                if(rows>MAX_ROWS||((Number)totals.get("raw_bytes")).longValue()>MAX_BYTES)throw tooLarge();
                var args=new ArrayList<>(filter.args());args.add(totals.get("watermark"));
                try(var out=new LimitedOutput(Files.newOutputStream(file),MAX_BYTES)){
                    db.query(connection->{var ps=connection.prepareStatement("SELECT * FROM received_message WHERE "+filter.where()+" AND message_id<=? ORDER BY message_id ASC LIMIT "+MAX_ROWS);for(int i=0;i<args.size();i++)ps.setObject(i+1,args.get(i));ps.setFetchSize(Integer.MIN_VALUE);ps.setQueryTimeout(15);return ps;},rs->{
                        if(System.nanoTime()-started>java.util.concurrent.TimeUnit.SECONDS.toNanos(15))throw new ReceiverFailure(413,"导出准备时间过长，请缩小筛选范围");
                        try{var row=new LinkedHashMap<String,Object>();var meta=rs.getMetaData();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rs.getObject(i));json.writeValue(new NonClosingOutput(out),exportRecord(row));out.write('\n');count[0]++;}
                        catch(IOException e){throw failure(e,"导出文件写入失败，请重试");}
                    });
                }catch(IOException e){throw failure(e,"导出文件准备失败，请重试");}
                if(count[0]!=rows)throw new ReceiverFailure(503,"导出快照不完整，请重试");
            });
            return new Download(file,"receiver-export-"+System.currentTimeMillis()+".jsonl","application/x-ndjson;charset=UTF-8",Files.size(file),count[0],slots);
        });
    }
    private Map<String,Object> exportRecord(Map<String,Object> original){
        var row=new LinkedHashMap<>(original);row.put("message_key",ReceivingService.representation((byte[])row.get("message_key")));row.put("message_value",ReceivingService.representation((byte[])row.get("message_value")));
        try{row.put("headers",json.readTree(row.remove("headers_json").toString()));}catch(Exception e){throw new ReceiverFailure(503,"消息头记录不完整，无法导出");}
        if(row.get("received_at") instanceof Timestamp stamp)row.put("received_at",stamp.toInstant().toString());return row;
    }
    static String kind(byte[] bytes){
        final String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(Exception e){return "bin";}
        try(var parser=new com.fasterxml.jackson.core.JsonFactory().createParser(text)){if(parser.nextToken()==null)return "txt";parser.skipChildren();if(parser.nextToken()!=null)return "txt";return "json";}catch(Exception e){return "txt";}
    }
    private record Filter(String where,List<Object> args){}
    private static Filter filter(String task,String subject,String topic,Integer partition,String from,String until){
        StringBuilder where=new StringBuilder("task_id=? AND EXISTS(SELECT 1 FROM receiving_task own WHERE own.task_id=received_message.task_id AND own.subject_id=?)");var args=new ArrayList<Object>();args.add(task);args.add(subject);
        if(topic!=null&&!topic.isBlank()){where.append(" AND topic=?");args.add(topic);}if(partition!=null){if(partition<0)throw new ReceiverFailure(400,"分区不合法");where.append(" AND partition_id=?");args.add(partition);}
        Instant start=parse(from),end=parse(until);if(start!=null&&end!=null&&!start.isBefore(end))throw new ReceiverFailure(400,"接收时间范围不合法");
        if(start!=null){where.append(" AND received_at>=?");args.add(Timestamp.from(start));}if(end!=null){where.append(" AND received_at<?");args.add(Timestamp.from(end));}return new Filter(where.toString(),List.copyOf(args));
    }
    private static Instant parse(String value){if(value==null||value.isBlank())return null;try{var time=Instant.parse(value);int year=time.atOffset(ZoneOffset.UTC).getYear();if(year<1000||year>9999||time.getNano()%1000000!=0)throw new IllegalArgumentException();return time;}catch(Exception e){throw new ReceiverFailure(400,"接收时间格式不正确");}}
    static ReceiverFailure tooLarge(){return new ReceiverFailure(413,"导出超过10000条或50MB，请缩小筛选范围");}
    private static ReceiverFailure failure(Exception e,String message){for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof ReceiverFailure r)return r;return new ReceiverFailure(503,message);}
    static final class LimitedOutput extends FilterOutputStream {
        long size;final long limit;LimitedOutput(OutputStream out,long limit){super(out);this.limit=limit;}
        private void check(long count){if(count>limit-size)throw tooLarge();size+=count;}
        public void write(int value)throws IOException{check(1);out.write(value);}public void write(byte[] value,int start,int count)throws IOException{check(count);out.write(value,start,count);}
    }
    private static final class NonClosingOutput extends FilterOutputStream {NonClosingOutput(OutputStream out){super(out);}public void close()throws IOException{flush();}public void write(byte[] b,int off,int len)throws IOException{out.write(b,off,len);}}
}
