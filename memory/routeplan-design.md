---
name: routeplan-design
description: "RoutePlan 权威规格(#132-#137全落码,#136 SSE真流式807全绿)：8字段RoutePlanCandidate+4跨字段约束+服务端收敛(许可范围/6 policy_constraints)+12-intent→RoutePlan映射表+复合优先级+starter calc tools先做+Intent 7认知→12业务对齐"
metadata:
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-12T12:53:49.494Z
---

RoutePlan 路由权威规格（用户 2026-09-10 给参考系统 Pydantic 设计钦定，取代先前推断字段）。

**RoutePlanCandidate（8字段，extra="forbid"+strict，模型产出候选）：**
1.intent(12选1) 2.needs_rag(bool) 3.needs_business_tools(bool) 4.required_tools(list[str],只能从tool_candidates选) 5.knowledge_domains(list[str],只能4域) 6.risk_level(low/medium/high) 7.requires_workflow(bool) 8.fallback_policy(Literal6值:safe_deterministic_path/ask_order_id/knowledge_only/tool_first/workflow_first/transfer_to_human)
注：能力不用set，拆 needs_rag+needs_business_tools+requires_workflow 三bool；workflow类型(退款/退货)在intent里不在单独字段；无mode/serialOrder(串并行运行时DAG)；risk三档非两档。

**4 跨字段约束（validate_cross_field_contract）：**
1.required_tools非空→needs_business_tools必须true 2.knowledge_domains非空→needs_rag必须true 3.requires_workflow=true→risk_level必须high 4.requires_workflow=true→fallback_policy必须workflow_first
校验失败：候选None→fallback_reason="invalid_model_route_candidate"→用兜底意图(确定性)。路由环节永不崩溃。

**服务端收敛（per-intent许可范围）：**
确定性基线=必选能力(必选工具/知识域/风险下限/fallback_policy)；模型候选只能在 allowed_tools_by_intent/allowed_domains_by_intent 内补充，❌不能发明工具 ❌不能跨域 ❌不能降风险下限。
policy_constraints审计数组:["structured_candidate_validated","tool_allowlist","knowledge_domain_allowlist","risk_floor","workflow_boundary","required_entity_gate"]
route_plan.source="llm_with_policy_constraints"(候选采纳,conf=0.9)/"deterministic_fallback"(纯确定性,conf=0.75)

**4 知识域：** faq / after_sale_policy / received_return_policy / promotion_and_member_policy

**intent→RoutePlan 映射表（确定性基线，11示+1待补共12）：**
order_query→get_order_logistics,-,low,-,tool_first
refund_status_query→get_refund_status,-,low,-,tool_first
refund_request→get_order_detail,after_sale_policy,high,yes,workflow_first
return_request→get_order_detail,received_return_policy,high,yes,workflow_first
product_query→search_products,promotion_and_member_policy(含活动/优惠/满减/会员时),low,-,tool_first
faq_query→-,faq,low,-,knowledge_only
promotion_query→-,promotion_and_member_policy,low,-,knowledge_only
low_confidence_query→-,promotion_and_member_policy,low,-,transfer_to_human
security_request→-,-,high,-,transfer_to_human
degradation_request→-,-,medium,-,transfer_to_human
general_chat→-,-,low,-,safe_deterministic_path

**复合优先级（route_contract.md）：** 高风险诉求优先(查物流+退款→refund_request)；商品Tool优先再补RAG(耳机多少钱+库存+满减→product_query)；纯知识无商品Tool(满减+会员券叠加→promotion_query)。

**Intent 对齐（大决策,倾向replace,待确认序列）：** 参考系统12业务intent取代我现7认知intent(CHIT_CHAT/REASONING/LONG_CONTEXT/STRUCTURED_EXTRACTION/TRANSFER_TO_HUMAN/INJECTION/OTHER)。blast radius:KeywordTriage规则/IntentRecognizerImpl(prompt列12+parse)/RouteDispatchStep/ChatLlmService thinking-disable(general_chat恒关,其他由开关定)/大量测试。general_chat←CHIT_CHAT,security_request←INJECTION。序列:starter calc tools之后做(最小路径先)。

**与护栏三层的缝合（同一件事两视角）：** L1确定性护栏(明确动作性高风险→直达WORKFLOW)=per-intent确定性基线(refund/return:high+workflow+workflow_first)+risk_floor+workflow_boundary收敛(模型不可降风险)；L2路由否决=workflow_boundary/risk_floor否决模型对非动作文本误触发+跨字段约束；L3兜底=deterministic_fallback(source=det,conf=0.75),route_model不可用或候选invalid时。护栏分句假意图排除(否定/已申请/询问)是refund_request的Layer1入口门。

**starter calc tools（用户要求先做,bootstrap tool path）：** 计算三角形面积(base,height)→0.5bh / 计算圆形面积(radius)→πr² / 打印99乘法表。先做这3个trivial工具+验工具调用闭环(tool-calling primitive：模型function-call→ToolExecutor→reparse自纠正→结果)，后续真业务工具(order/logistics/inventory/price)接上即用。不依赖Intent重构。

**运行时调度（不在plan）：** 串并行按工具依赖元数据动态DAG，无依赖并行(CompletableFuture)有依赖串行；Workflow-HITL子流固定子图走P14 LangGraph。

**缺口实现优先级：** ①starter calc tools+tool-calling闭环(先做)→②RoutePlan 8字段(改RoutePlanTest)→③12-intent对齐+映射表→④收敛层(4约束+allowlists+risk_floor+workflow_boundary+required_entity_gate+policy_constraints审计)→⑤复合优先级→⑥CapabilityStage DAG调度(⚠用户2026-09-12纠正**作废**:RoutePlan无DAG,低风险=固定链+#135,通用per-intent DAG机器已回滚)→⑦Workflow-HITL子图(P14+P11)(✅**已实现**为高风险固定LangGraph子图:RefundWorkflowGraph+WorkflowExecutionStep@670,独立WorkflowApprovalDecision seam≠P11 HitlDecision[决策D],真seam延后;见[[high-risk-workflow-design]])→⑧真业务工具接入(**已实现**2026-09-12,见[[business-tools-workflow-dag]]：6 业务 @Tool—OrderQueryTool/UserQueryTool/ProductQueryTool(RUNTIME)+ReturnPolicyTool/RefundPolicyTool/PromotionPolicyTool(RAG)+3 typed mock 服务+PolicyQueryService 单入口 seam[mock,后期单点切真 RAG via RagPolicyQueryService 委托 HybridRetriever]+@ToolChannel/ToolCategory 3 通道路由+ToolCallExecutor 返 List<ToolCallResult>+ToolExecutionStep.routeResults；真 API/RAG 后端仍 mock 先行)。

~~当前RoutePlan.java(3字段含mode/serialOrder)+RoutePlanTest(bothParallel/bothSerial)须回炉~~（已回炉为 8 字段 candidate 版，见上文实现进展）。

**实现进展(2026-09-12,788全绿)：** ①starter calc tools+②RoutePlan 8字段已完成(LC4j@Tool,ToolCallExecutor退役手撸路径,见[[langchain4j-boot4-compat-findings]]);③收敛层 #132 GREEN(24测)：RoutePlan回炉(candidate+Source+confidence+policyConstraints/扁平访问器/deterministic工厂)+RoutePlanBaselines(11+1 intent基线,baseline()派生needsRag/needsBusinessTools自洽)+RoutePlanContractValidator(4跨字段约束)+RoutePlanRuleMatcher(converge:5政策约束顺序校验首违即兜底,audit=structured_candidate_validated/tool_allowlist/knowledge_domain_allowlist/risk_floor/workflow_boundary,risk_floor用RiskLevel.ordinal()比较依赖LOW<MEDIUM<HIGH声明序);④RoutePlanner #133 GREEN(20测)：rule短路(已知确定性意图baseline无能力→零LLM,security直达TRANSFER_TO_HUMAN)→LLM(RouteCandidateSource seam,LlmRouteCandidateSource真实现=ChatLlmService.decide关思考+RouteCandidateParser)→rule收敛(converge)/rule兜底(LLM挂→fallbackIntent基线,未知→general_chat);RouteCandidateParser用Jackson3(tools.jackson,与LC4j com.fasterxml包名不同无冲突)JsonMapper.builder().propertyNamingStrategy(SNAKE_CASE).enable(ACCEPT_CASE_INSENSITIVE_ENUMS).disable(FAIL_ON_UNKNOWN_PROPERTIES),readValue(String)在v3已移除故readTree→treeToValue,fence/prose取首{末}提取,缺必选枚举→empty守NPE;铁律①javap钉了MapperBuilder.propertyNamingStrategy/enumNamingStrategy+treeToValue签名再写。⑤RoutePlanStep装配 #134 GREEN(28测,749→777)：IntentRouteMapper(7认知Intent→12业务fallbackIntent占位:CHIT_CHAT→general_chat/INJECTION→security_request/TRANSFER_TO_HUMAN→degradation_request确定性短路,REASONING/LONG_CONTEXT/STRUCTURED_EXTRACTION/OTHER→null=交route_model,全量7→12枚举replace后退役)+RoutePromptBuilder(8字段spec+tool_candidates从baselines派生单一真源+4域/3风险/6fallback/intent枚举选项空间,history transcript复用QueryRewriter[角色]内容模式,null安全)+PipelineContext.routePlan字段(additive,@Order605紧随RouteDispatchStep600先于HitlStep610)+RoutePlanStep(@Component@605:读intent→map→buildPrompt→planner.plan→setRoutePlan→恒Proceed,路由永不崩,query优先standardQuery.text()回退rawInput)+RoutePlanConfig(@Configuration无属性门控,7 bean装配,noop模式decide返占位非JSON→parser empty→rule兜底general_chat无hang;prod打真route_model);@Order605不撞HitlStep@610;@SpringBootTest 10个默认llm.enabled缺省→NoopModelExecutor→@605产deterministic fallback routePlan无hang无context失败。⑥#135 渐进消费·source门控 GREEN(11测,777→788)：HitlStep@610/RagStep@660/ToolExecutionStep@650 三消费者在routePlan.source()==LLM_WITH_POLICY_CONSTRAINTS时按routePlan决策(HITL:fallbackPolicy==TRANSFER_TO_HUMAN→触发;RAG:!needsRag→跳过Proceed非降级;Tool:!needsBusinessTools→跳过省一次function-calling),DETERMINISTIC_FALLBACK/null回退现有Intent逻辑(noop测试/兜底候选不采信routePlan,现有行为/全量@SpringBootTest不破);TDD每消费者RED(needsRag/needsBusinessTools=false旧代码跑链→撞)→GREEN(source门控跳过);**DAG串并行+required_tools per-route子集(ToolProvider)+knowledge_domains缩范围均延后**(设计"运行时调度不在plan"+Slice4,归12-intent对齐big-bang)。#137 GREEN(792全绿,4 smoke gated)：RoutePlanWiringIntegrationTest 4/4显式@SpringBootTest(properties="llm.enabled=false")锁noop确定性——避dev shell export LLM_ENABLED=true+SF_KEY时裸@SpringBootTest打真route_model("退款"→refund_request真实候选)与noop兜底general_chat断言冲突(非prod bug,测试越界进真model域;测试声明域=只钉noop链路,真model冒烟须export SF_KEY+DS_KEY另测)。

#136 ProgressEmitter seam+SSE多事件进度 GREEN(807全绿,4 smoke gated,+15测：ProgressEmitterTest3/SseProgressEmitterTest4/PipelineOrchestratorTest+4/GraphNodeTest+4/ChatControllerTest净0替换)：用户钦定"富事件+真流式"非最小seam。①seam=ProgressEmitter函数接口(NO_OP静态λ默认)+ProgressEvent sealed(StepStarted/StepFinished+Outcome enum PROCEED/SHORT_CIRCUIT/DEGRADE/RETRY/EXCEPTION,富化变体RouteDecided/ToolCalled/RagRetrieved/ReplyReady后补permits)——经**PipelineContext.emitter()每请求实例**注入(**不改PipelineExecutor.run签名**,故线性+图两引擎都读context.emitter(),换引擎不换seam);②线性编排器PipelineOrchestrator.run每step emit StepStarted(前)+StepFinished(outcome→scenario)(后,5点:Proceed/ShortCircuit/Degrade/Exception+Retry等价Proceed);③图模式对齐在**GraphNode.asAsyncNodeAction**(非GraphExecutor,因图把step包进GraphNode执行)同款emit,镜像线性4分支;④SseProgressEmitter桥(ProgressEvent→SSE event:name=variant snake_case,data=ObjectMapper序列化record prod non_null省null scenario/异常兜底{};send IOException吞+记日志不反噬流水线best-effort);⑤ChatController chatStream返回**SseEmitter非ResponseEntity<String>**(@Value app.sse.timeout-ms:120000)+SseStreamConfig @Bean sseTaskExecutor(SimpleAsyncTaskExecutor"sse-",每流一线程dev用/高并发换ThreadPool后置)+@Qualifier("sseTaskExecutor")避开Boot默认applicationTaskExecutor歧义+两构造器(4参测试默认120s/5参@Autowired);runToSse包级seam(SseProgressEmitter注入→跑流水线→终端reply_ready事件负载=ChatResponse JSON→complete/异常completeWithError不吞),/chat走NO_OP零开销;⑥测 seam=CapturingSseEmitter override send(SseEventBuilder)→build() Set<DataWithMediaType>逐项getData()(截获wire-name条"event:step_started\n"+data JSON双类项,故contains事件名token+reply/sessionId)。NO_OP默认保792存量不破(additive)。

**待定点：** ①LC4j接入已钉坐标(mvnrepository用户代查:langchain4j-core 1.19.0 GA + langchain4j-open-ai-spring-boot4-starter 1.19.0-beta29,无BOM;1.20.0-beta30是幻觉错版);core已加pom+compile验共存GREEN,boot4-starter待A/B定;SAA出局(SB3-only),定LC4j;②A/B叉口:AiServices接管循环(退役ToolExecutor/Detector,LC4j作engine)vs 保pipeline用@Tool做schema(扩OpenAiModelExecutor发tool schema+解析tool_calls);③Intent 7→12对齐(replace,blast radius大);④旗舰route_model两档vs同小模型(不阻塞)。

关联 [[phase-llm-primary-backup-breaker]](decide关思考)、[[phase9-10-capability-tool-rag-design]](现有ToolExecutor/ToolExecutionStep/Reparser)、[[phase14-langgraph-design]](Workflow子图+调度)、[[phase11-12-hitl-output-design]](HITL接workflow末步)、[[phase17-18-plan]](CircuitBreaker)、[[phase6-7-chitchat-fast-path]](KeywordTriage rule-first)、[[phase20-retrieval-enhancement-plan]]/[[phase21-hybrid-retrieval-design]](RAG path)。
