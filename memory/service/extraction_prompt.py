"""Extraction prompt builder for memory service — supports 7 memory types."""
from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


def detect_language(content: str) -> str:
    """Detect language based on Chinese character ratio."""
    if not content:
        return "en"
    chinese_chars = sum(1 for c in content if "\u4e00" <= c <= "\u9fff")
    ratio = chinese_chars / len(content) if content else 0
    return "zh" if ratio > 0.1 else "en"


def build_extraction_prompt(content: str) -> str:
    """Build the extraction prompt, auto-detecting language from content."""
    language = detect_language(content)
    if language == "en":
        return _build_en_prompt(content)
    return _build_zh_prompt(content)


def _build_en_prompt(content: str) -> str:
    return f"""Extract structured memory information from the user message below.
Use the FULL conversation context available to you (not just the ingested content) to produce accurate extractions.
Return results strictly in JSON format.

The content to extract from:
<user_content>
{content}
</user_content>

Extract the following memory types:

1. **Facts**: Objective, persistent information about the user and people mentioned
   - Format: {{"content": "fact description", "category": "category", "confidence": 0.0-1.0, "importance": 1-10, "entities": {{"people": [...], "locations": [...], "topics": [...]}}, "emotion": {{"valence": -1.0~1.0, "arousal": 0.0~1.0, "label": "emotion"}} or null}}
   - Category options: identity, work, skill, hobby, personal, education, location, health, relationship, finance, values
   - Facts capture ONLY persistent, reusable attributes (occupation, hobbies, skills, personality, relationships, values, preferences)
   - One-time events belong in Episodes, NOT Facts
   - Each fact must be atomic (one piece of info) and self-contained (explicit subject, no pronouns)
   - Preserve specifics, never generalize

2. **Episodes**: Events, experiences, temporal information
   - Format: {{"content": "event description", "timestamp": "ISO date or null", "timestamp_original": "original time expression or null", "people": ["person1"], "location": "place or null", "confidence": 0.0-1.0, "importance": 1-10, "entities": {{"people": [...], "locations": [...], "topics": [...]}}, "emotion": {{"valence": -1.0~1.0, "arousal": 0.0~1.0, "label": "emotion"}} or null}}

3. **Procedural**: Instructions, commands, workflow steps, tool usage patterns
   - Format: {{"content": "procedural description", "category": "category"}}
   - Category options: workflow, tool_usage, coding_pattern, configuration, process
   - Procedural memories capture HOW to do things — instructions the user gave to an agent,
     commands to run, patterns to follow, configuration to apply
   - These are NOT personal facts about the user — they are work/process knowledge

4. **Triples**: Entity-relation triples
   - Format: {{"subject": "entity", "subject_type": "type", "relation": "relation", "object": "entity", "object_type": "type", "content": "original description", "confidence": 0.0-1.0}}
   - subject_type/object_type options: user, person, organization, location, skill, entity
   - For the user: subject="user", subject_type="user"
   - Skip triples with confidence < 0.6

5. **Decisions**: Intentional choices made with clear rationale
   - Format: {{"content": "description of the decision made", "rationale": "why this choice was made", "project": "project name or null", "confidence": 0.0-1.0}}
   - Capture deliberate architectural, technical, or process decisions
   - Each decision must be self-contained — include the subject and context explicitly
   - project: infer from context if possible, otherwise null

6. **Rejections**: Approaches or options explicitly excluded with stated reason
   - Format: {{"content": "description of the approach that was rejected", "reason": "why it was excluded", "project": "project name or null", "confidence": 0.0-1.0}}
   - Only extract explicit rejections — do not infer from silence
   - Each rejection must be self-contained — include what was rejected and why
   - project: infer from context if possible, otherwise null

7. **Conventions**: Project or team rules, norms, and standards that should be consistently applied
   - Format: {{"content": "description of the convention or rule", "scope": "scope of applicability", "project": "project name or null", "confidence": 0.0-1.0}}
   - scope examples: "all projects", "Python code", "API design", "deployment", "naming"
   - Capture standing rules, coding standards, naming conventions, workflow norms
   - Each convention must be self-contained — include explicit subject and scope
   - project: infer from context if possible, otherwise null

Requirements:
- Only extract explicitly mentioned information
- Confidence represents extraction certainty (0.0-1.0)
- Return empty list if no information for a category
- All content fields must be self-contained (no pronouns, explicit subject)
- Must return valid JSON only, no additional text

Return format (JSON only):
```json
{{
  "facts": [...],
  "episodes": [...],
  "procedural": [...],
  "triples": [...],
  "decisions": [...],
  "rejections": [...],
  "conventions": [...]
}}
```"""


def _build_zh_prompt(content: str) -> str:
    return f"""分析以下用户消息，提取结构化记忆信息。
利用你可用的完整对话上下文（不仅仅是给定的内容）来进行准确提取。
请严格按照 JSON 格式返回结果。

需要提取的内容:
<user_content>
{content}
</user_content>

请提取以下记忆类型:

1. **Facts（事实）**: 用户及对话中提到的人物的持久性客观信息
   - 格式: {{"content": "事实描述", "category": "分类", "confidence": 0.0-1.0, "importance": 1-10, "entities": {{"people": [...], "locations": [...], "topics": [...]}}, "emotion": {{"valence": -1.0~1.0, "arousal": 0.0~1.0, "label": "情感描述"}} 或 null}}
   - category 可选: identity, work, skill, hobby, personal, education, location, health, relationship, finance, values
   - Facts 只捕获持久、可复用的属性（职业、爱好、技能、性格、关系、价值观、偏好）
   - 一次性事件应放入 Episodes，不要作为 Fact 重复
   - 每个 fact 必须是原子的（一条信息），必须有明确的主语（禁止代词）
   - 保留具体细节，禁止模糊泛化

2. **Episodes（情景）**: 事件、经历、时间相关信息
   - 格式: {{"content": "事件描述", "timestamp": "ISO日期或null", "timestamp_original": "原始时间表达或null", "people": ["人名"], "location": "地点或null", "confidence": 0.0-1.0, "importance": 1-10, "entities": {{"people": [...], "locations": [...], "topics": [...]}}, "emotion": {{"valence": -1.0~1.0, "arousal": 0.0~1.0, "label": "情感描述"}} 或 null}}

3. **Procedural（流程）**: 指令、命令、工作流步骤、工具使用模式
   - 格式: {{"content": "流程描述", "category": "分类"}}
   - category 可选: workflow, tool_usage, coding_pattern, configuration, process
   - Procedural 记忆捕捉如何做某事——用户给 Agent 的指令、要运行的命令、要遵循的模式、要应用的配置
   - 这些不是用户的个人信息——而是工作/流程知识

4. **Triples（实体关系三元组）**: 结构化关系
   - 格式: {{"subject": "主体", "subject_type": "类型", "relation": "关系", "object": "客体", "object_type": "类型", "content": "原始描述", "confidence": 0.0-1.0}}
   - subject_type/object_type 可选: user, person, organization, location, skill, entity
   - 用户自身: subject="user", subject_type="user"
   - confidence < 0.6 的 triple 不要输出

5. **Decisions（决策）**: 有明确理由的主动选择
   - 格式: {{"content": "所做决策的描述", "rationale": "做出此选择的原因", "project": "项目名称或null", "confidence": 0.0-1.0}}
   - 捕捉刻意的架构、技术或流程决策
   - 每条决策必须自包含——明确写出主语和背景
   - project: 尽量从上下文推断，无法推断则为 null

6. **Rejections（排除项）**: 明确被排除的方案或选项，含明确原因
   - 格式: {{"content": "被排除方案的描述", "reason": "被排除的原因", "project": "项目名称或null", "confidence": 0.0-1.0}}
   - 只提取明确被排除的内容——不要从沉默中推断
   - 每条排除项必须自包含——明确写出什么被排除以及原因
   - project: 尽量从上下文推断，无法推断则为 null

7. **Conventions（约定）**: 项目或团队的规则、规范和标准，应持续遵守
   - 格式: {{"content": "约定或规则的描述", "scope": "适用范围", "project": "项目名称或null", "confidence": 0.0-1.0}}
   - scope 示例: "所有项目", "Python代码", "API设计", "部署流程", "命名规范"
   - 捕捉长期规则、编码标准、命名约定、工作流规范
   - 每条约定必须自包含——明确写出主语和适用范围
   - project: 尽量从上下文推断，无法推断则为 null

要求:
- 只提取明确提到的信息，不要推测
- confidence 表示提取的确信度 (0.0-1.0)
- 如果某类没有信息，返回空列表
- 所有 content 字段必须自包含（不使用代词，有明确主语）
- 必须返回有效的 JSON 格式，不要有其他文字说明

返回格式（只返回 JSON）:
```json
{{
  "facts": [...],
  "episodes": [...],
  "procedural": [...],
  "triples": [...],
  "decisions": [...],
  "rejections": [...],
  "conventions": [...]
}}
```"""
