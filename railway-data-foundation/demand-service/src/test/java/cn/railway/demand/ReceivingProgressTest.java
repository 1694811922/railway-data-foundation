package cn.railway.demand;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ReceivingProgressTest {
    Map<String,Object> compare(long first,long end,Long committed,Long next){return ReceivingProgressService.compare(new KafkaConnections.Positions("cluster:topic","topic",Map.of(0,new KafkaConnections.Position(first,end,committed))),next==null?Map.of():Map.of(0,next));}
    Map<?,?> summary(Map<String,Object> value){return (Map<?,?>)value.get("summary");}
    @Test void emptyAvailableRangeIsNotAnError(){var s=summary(compare(0,0,null,0L));assertThat(s.get("status")).isEqualTo("NO_AVAILABLE_MESSAGES");assertThat(s.get("remaining_positions")).isEqualTo(0L);}
    @Test void caughtUpAndBacklogUseDatabaseCheckpointNotGroupOffset(){assertThat(summary(compare(0,10,0L,10L)).get("status")).isEqualTo("CAUGHT_UP");var s=summary(compare(0,10,10L,4L));assertThat(s.get("remaining_positions")).isEqualTo(6L);assertThat(s.get("status")).isEqualTo("BACKLOG");}
    @Test void missingCheckpointStaysUnknownEvenWhenGroupHasCommitted(){var s=summary(compare(0,10,10L,null));assertThat(s.get("status")).isEqualTo("NOT_INITIALIZED");assertThat(s.get("remaining_positions")).isNull();}
    @Test void expiredAndAheadPositionsAreNotClampedToZero(){for(var value:List.of(compare(5,10,4L,4L),compare(0,10,12L,12L))){assertThat(summary(value).get("status")).isEqualTo("POSITION_INVALID");assertThat(summary(value).get("remaining_positions")).isNull();}}
    @Test void combinesAllPartitionsInAscendingOrderAndKeepsUnknownTotals(){var positions=new KafkaConnections.Positions("identity","topic",Map.of(2,new KafkaConnections.Position(0,8,null),0,new KafkaConnections.Position(0,3,3L)));var result=ReceivingProgressService.compare(positions,Map.of(0,3L));assertThat(summary(result).get("remaining_positions")).isNull();assertThat((List<?>)result.get("partitions")).hasSize(2);assertThat(((Map<?,?>)((List<?>)result.get("partitions")).get(0)).get("partition")).isEqualTo(0);}
    @Test void emptyMetadataCannotReportAHealthyZero(){assertThatThrownBy(()->ReceivingProgressService.compare(new KafkaConnections.Positions("id","topic",Map.of()),Map.of())).isInstanceOf(ReceiverFailure.class);}
}
