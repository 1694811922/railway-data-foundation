<script setup>
import { capabilitySupport } from '../utils/capabilityForm'
const supportRows=Object.entries(capabilitySupport).map(([type,description])=>({type,description,label:{REST_API:'REST接口',KAFKA_TOPIC:'Kafka消息主题',CDC_STREAM:'CDC变化流',READONLY_QUERY:'只读查询'}[type]}))
</script>
<template>
  <div class="stack">
    <div class="page-heading"><div><h1>系统说明</h1></div></div>
    <div class="panel"><div class="panel-title">数据与控制分离</div><p class="about-text">业务明细保留在源系统。底座登记数据源、扫描结构、管理资源与字段、纳管访问能力，不保存业务明细，也不代理业务数据传输。</p>
      <div class="data-flow"><span>业务数据库</span><b>→</b><span>提供服务</span><b>→</b><span>调用方</span></div>

    </div>
    <div class="panel"><div class="panel-title">四种访问能力的当前支持范围</div><el-table :data="supportRows" data-testid="about-capability-support"><el-table-column prop="label" label="能力类型" width="170" /><el-table-column prop="description" label="当前功能与限制" min-width="300" /></el-table></div>
    <div class="grid-2">
      <div class="panel"><div class="panel-title">建议操作顺序</div><ol class="about-text"><li>登记数据源连接配置，测试连接。</li><li>扫描数据库结构，检查表与字段。</li><li>从扫描结果创建资源，人工补全业务信息并发布。</li><li>登记访问能力，按支持范围验证、显式启用或停用。</li></ol></div>
      <div class="panel"><div class="panel-title">真实与演示数据</div><p class="about-text">默认使用真实接口。显式演示模式只修改当前浏览器的演示元数据，页面会显示演示标记；真实写入失败不转为演示成功。</p></div>
    </div>
  </div>
</template>
<style scoped>
.about-text { line-height:1.9; color:#536d81; }
.data-flow { display:flex; align-items:center; justify-content:center; gap:24px; margin:24px 0; }
.data-flow span { background:#eef6f8; padding:16px 28px; color:#176b80; border:1px solid #d9e8ee; border-radius:8px; }
.data-flow b { color:#8196a6; }
@media(max-width:850px) { .data-flow { gap:10px; } .data-flow span { padding:12px; } }
</style>
