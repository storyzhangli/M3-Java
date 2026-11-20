# C11 前端安全静态扫描证据（D37）

- 扫描时间：2026-11-20（本地）
- 扫描范围：D37/D37_RAG_ESAB/src/main/resources/static/js/{app.js, sse-parser.js, templates.js}
- 扫描模式：innerHTML|outerHTML|document.write|insertAdjacentHTML|eval(|new Function|dangerouslySetInnerHTML
- 结果：仅 2 处命中，均为注释（app.js:7、app.js:119），无任何实际危险 sink 调用。
- 渲染方式核实：服务端文本经 textContent/createTextNode 渲染（app.js 注释与实现一致）。
- node --test 结果：6/6 PASS（见 C11-frontend-node-test.txt）。
- 首次运行失败说明：`node --test <目录>` 在 Node v22.23.2 下被当作模块解析报 MODULE_NOT_FOUND；
  改为直接传测试文件路径后通过。属调用方式问题，非产品代码缺陷。
