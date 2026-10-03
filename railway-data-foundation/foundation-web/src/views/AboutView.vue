<template>
  <div class="stack">
    <div class="page-heading"><div><div class="eyebrow">ABOUT / ARCHITECTURE</div><h1>系统说明</h1><p>铁路统一数据底座虚拟接入与共享治理原型系统</p></div></div>
    <div class="panel"><div class="panel-title">架构原则</div><p style="line-height:1.9;color:#536d81">业务数据保留在源系统。统一数据底座管理 Source、Resource、Capability 等元数据，不保存业务明细，也不代理业务数据传输。REST、Kafka Topic 与 CDC Stream 是同一 Resource 的不同访问能力。</p></div>
    <div class="grid-2">
      <div class="panel"><div class="panel-title">当前真实后端能力</div><p>数据源管理、元数据扫描、资源发布、字段维护和接口能力登记已接入真实接口。</p><p>独立 Provider 可直读 Source，并将指定事件发送到 Kafka；Foundation 可验证 REST 和 Kafka Topic 元数据。</p></div>
      <div class="panel"><div class="panel-title">当前演示边界</div><p>前端在后端不可用或显式演示模式下使用独立 mock，并标注“演示数据”。</p><p class="dim">Kafka 运行需另行配置 Broker；CDC 验证、能力启用及后续治理模块尚未开放。</p></div>
    </div>
  </div>
</template>
