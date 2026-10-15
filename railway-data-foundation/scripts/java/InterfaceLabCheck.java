import java.nio.ByteBuffer;
import java.util.List;

/** Deterministic checks for experiment units, deduplication and percentile/CSV calculations. */
public final class InterfaceLabCheck {
    static void require(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args) {
        byte[] body=InterfaceLab.payload(7,System.nanoTime(),512);require(body.length==512);require(ByteBuffer.wrap(body).getInt()==7);
        var observations=new InterfaceLab.Observation();observations.received(body);observations.received(body);
        require(observations.unique.size()==1);require(observations.duplicates.get()==1);require(observations.bytes.get()==512);
        observations.latency.clear();observations.latency.addAll(List.of(5.0,1.0,4.0,2.0,3.0));
        require(observations.percentile(.50)==3.0);require(observations.percentile(.95)==5.0);
        String[] columns=InterfaceLab.row("peak","KAFKA",1,observations,System.nanoTime(),0).split(",");
        require(columns.length==14);require(columns[2].equals("1"));require(columns[3].equals("1"));require(columns[5].equals("1"));require(columns[6].equals("512"));require(columns[9].equals("3.000"));require(columns[10].equals("5.000"));
        System.out.println("Experiment tool checks passed: payload bytes/sequence, duplicate observations, percentile order and CSV units.");
    }
}
