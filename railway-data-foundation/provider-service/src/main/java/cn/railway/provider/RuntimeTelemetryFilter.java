package cn.railway.provider;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;

/** Counts bytes while forwarding them. Business response bodies are never buffered or copied to the reporter. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public class RuntimeTelemetryFilter extends OncePerRequestFilter {
    private final RuntimeTelemetryReporter reporter;
    public RuntimeTelemetryFilter(RuntimeTelemetryReporter reporter){this.reporter=reporter;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getMethod().equals("GET")&&request.getRequestURI().equals("/containers/events")
                ||request.getMethod().equals("POST")&&(request.getRequestURI().matches("/containers/events/[^/]+/publish")||request.getRequestURI().matches("/api/v1/production/capabilities/[^/]+/records/[^/]+/send")));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        var capture=new RuntimeCapture(request.getMethod().equals("GET")?"REST_PROVIDER":"KAFKA_PRODUCER",request.getHeader("X-Relationship-Id"),request.getHeader("X-Capability-Id"));
        request.setAttribute(RuntimeCapture.ATTRIBUTE,capture);response.setHeader("X-Trace-Id",capture.traceId);
        var wrapped=new CountingResponse(response);boolean failed=false;
        try{chain.doFilter(request,wrapped);wrapped.finish();}
        catch(ServletException|IOException|RuntimeException e){failed=true;throw e;}
        finally {
            int status=wrapped.getStatus();if(failed&&status<500&&!wrapped.isCommitted())status=500;
            try{reporter.submit(capture.point,capture.finish(status,wrapped.bytes,failed));}catch(RuntimeException ignored){} // metadata submission cannot alter the authorized result
        }
    }
    private static final class CountingResponse extends HttpServletResponseWrapper {
        long bytes;
        ServletOutputStream stream;
        PrintWriter writer;
        CountingResponse(HttpServletResponse response){super(response);}
        @Override public ServletOutputStream getOutputStream()throws IOException {
            if(stream==null){var target=super.getOutputStream();stream=new ServletOutputStream(){
                @Override public boolean isReady(){return target.isReady();}
                @Override public void setWriteListener(WriteListener listener){target.setWriteListener(listener);}
                @Override public void write(int value)throws IOException{target.write(value);bytes++;}
                @Override public void write(byte[] values,int offset,int length)throws IOException{target.write(values,offset,length);bytes+=length;}
                @Override public void flush()throws IOException{target.flush();}
                @Override public void close()throws IOException{target.close();}
            };}return stream;
        }
        @Override public PrintWriter getWriter()throws IOException{if(writer==null)writer=new PrintWriter(new OutputStreamWriter(getOutputStream(),getCharacterEncoding()));return writer;}
        void finish()throws IOException{if(writer!=null){writer.flush();if(writer.checkError())throw new IOException("响应输出未完成");}}
        @Override public void reset(){super.reset();bytes=0;}
        @Override public void resetBuffer(){super.resetBuffer();bytes=0;}
    }
}
