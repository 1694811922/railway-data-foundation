export const progressLabels={NO_AVAILABLE_MESSAGES:'主题当前无可用消息',NOT_INITIALIZED:'尚未建立接收检查点',BACKLOG:'存在待接收位置',CAUGHT_UP:'已追平查询时位置',POSITION_INVALID:'快照检查点超出可用范围',HISTORY_EXPIRED:'检查点低于保留起点'}
export const progressHints={NO_AVAILABLE_MESSAGES:'等待提供方发送消息。源表已有记录不会自动进入Kafka。',NOT_INITIALIZED:'任务启动后才建立各分区检查点；新分区也需等待任务处理。',BACKLOG:'存在尚未落库的位置范围，请结合任务状态判断是否需要启动或恢复。',CAUGHT_UP:'暂无待接收位置差；后续新消息仍会继续接收。',POSITION_INVALID:'请先刷新核对任务状态，不要重置位置或重复发送。'}
const offset=value=>value===null||Number.isSafeInteger(value)&&value>=0
export function receivingProgress(value){
 if(!value?.task?.task_id||!value.topic||!Array.isArray(value.partitions)||!value.partitions.length||!progressLabels[value.summary?.status]||!offset(value.summary.remaining_positions)||value.summary.partition_count!==value.partitions.length)throw new Error('接收进度响应不完整')
 const seen=new Set();for(const p of value.partitions){if(!Number.isInteger(p.partition)||p.partition<0||seen.has(p.partition)||!Number.isSafeInteger(p.earliest_offset)||p.earliest_offset<0||!Number.isSafeInteger(p.latest_offset)||p.latest_offset<p.earliest_offset||!offset(p.next_offset)||!offset(p.committed_offset)||!offset(p.remaining_positions)||!progressLabels[p.status])throw new Error('分区进度响应不完整');seen.add(p.partition)}
 return value
}
