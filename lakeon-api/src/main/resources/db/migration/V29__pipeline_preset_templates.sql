-- V29__pipeline_preset_templates.sql
-- 预置 Phase 1 组件 + 视频/文本 Pipeline 模板

-- ============================================================
-- 1. 预置组件 (tenant_id = NULL = 平台内置)
-- ============================================================

-- Video components
INSERT INTO pipeline_components (id, tenant_id, name, display_name, category, data_type, description, latest_version, created_at, updated_at)
VALUES
  ('comp_video_normalize',  NULL, 'video_normalize',     '视频规整适配', 'DATA_PREP', 'VIDEO',     'ffprobe 元数据提取 + ffmpeg 转码，标准化分辨率和格式', 1, NOW(), NOW()),
  ('comp_video_scene_split', NULL, 'video_scene_split',  '视频镜头切分', 'EXTRACT',   'VIDEO',     'PySceneDetect 镜头检测 + ffmpeg 切片，fan_out 输出 clips', 1, NOW(), NOW()),
  ('comp_rule_filter',       NULL, 'rule_filter',        '规则清洗',     'FILTER',    'VIDEO',     '基于 ffprobe 元数据的规则过滤：时长/分辨率/长宽比/帧率/裁剪面积', 1, NOW(), NOW()),
  ('comp_video_crop',        NULL, 'video_crop',         '视频裁剪',     'CLEAN',     'VIDEO',     'ffmpeg cropdetect + crop 处理', 1, NOW(), NOW()),
  ('comp_model_filter_mock', NULL, 'model_filter_mock',  '模型清洗 (Mock)', 'FILTER', 'VIDEO',     '[Phase 1 Mock] 随机打分模拟 VQA/水印/字幕/光流检测', 1, NOW(), NOW()),
  ('comp_quality_check',     NULL, 'quality_check',      '质量检查',     'QC',        'UNIVERSAL', 'HUMAN_REVIEW 模式：暂停等待人工审核确认', 1, NOW(), NOW()),
  ('comp_video_label_mock',  NULL, 'video_labeling_mock','内容标注 (Mock)', 'LABEL',  'VIDEO',     '[Phase 1 Mock] 返回固定标签模拟 VICLIP/Caption/运镜', 1, NOW(), NOW()),
  ('comp_dataset_publish',   NULL, 'dataset_publish',    '发布数据集',   'PUBLISH',   'UNIVERSAL', '写入 Lance/Parquet 数据集 + 创建 dataset_version 记录', 1, NOW(), NOW()),

-- Text components
  ('comp_text_dedup',        NULL, 'text_dedup',         '文本去重',     'CLEAN',     'TEXT',      'MinHash LSH 近似去重', 1, NOW(), NOW()),
  ('comp_text_clean',        NULL, 'text_clean',         '文本清洗',     'CLEAN',     'TEXT',      'HTML 清理、空白标准化、URL 移除、长度过滤', 1, NOW(), NOW()),
  ('comp_text_tokenize',     NULL, 'text_tokenize',      '分词统计',     'EXTRACT',   'TEXT',      'tiktoken/jieba 分词 + 统计', 1, NOW(), NOW()),
  ('comp_text_quality',      NULL, 'text_quality_score', '文本质量评分', 'QC',        'TEXT',      '基于规则的多维度质量评分', 1, NOW(), NOW());

-- ============================================================
-- 2. 组件版本 (version 1)
-- ============================================================

INSERT INTO pipeline_component_versions (id, component_id, version, entrypoint, params_schema, input_schema, output_schema, output_branches, requires_gpu, requires_model, execution_mode, status, created_at)
VALUES
  -- video_normalize
  ('compv_vid_norm_v1', 'comp_video_normalize', 1,
   'lakeon.components.video.video_normalize',
   '{"target_resolution":{"type":"string","default":"1080p","enum":["360p","480p","720p","1080p","2k","4k"]},"target_format":{"type":"string","default":"mp4","enum":["mp4","avi","mkv"]}}',
   '{"type":"video","format":["mp4","avi","mkv","mov","flv"]}',
   '{"type":"video","format":"mp4"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- video_scene_split
  ('compv_scene_v1', 'comp_video_scene_split', 1,
   'lakeon.components.video.video_scene_split',
   '{"threshold":{"type":"number","default":27},"min_scene_length":{"type":"number","default":1.0}}',
   '{"type":"video","format":["mp4","avi"]}',
   '{"type":"video_clips","format":"mp4"}',
   NULL, FALSE, 'pyscenedetect', 'FUNCTION', 'PUBLISHED', NOW()),

  -- rule_filter
  ('compv_rule_v1', 'comp_rule_filter', 1,
   'lakeon.components.video.rule_filter',
   '{"min_duration":{"type":"number","default":3},"min_resolution":{"type":"number","default":480},"max_aspect_ratio":{"type":"number","default":2},"min_fps":{"type":"number","default":20},"min_crop_area":{"type":"number","default":5}}',
   '{"type":"video","format":"mp4"}',
   '{"type":"video","format":"mp4"}',
   '["passed","needs_crop","dropped"]', FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- video_crop
  ('compv_crop_v1', 'comp_video_crop', 1,
   'lakeon.components.video.video_crop',
   '{"target_aspect_ratio":{"type":"number","default":1.78}}',
   '{"type":"video","format":["mp4"]}',
   '{"type":"video","format":"mp4"}',
   '["passed","dropped"]', FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- model_filter_mock
  ('compv_model_filt_v1', 'comp_model_filter_mock', 1,
   'lakeon.components.video.model_filter_mock',
   '{"checks":{"type":"array","default":["vqa","watermark","subtitle","optical_flow"]},"thresholds":{"type":"object","default":{"vqa":0.3,"watermark":0.5,"subtitle":0.5,"optical_flow":0.2}}}',
   '{"type":"video_clips","format":"mp4"}',
   '{"type":"video_clips","format":"mp4"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- quality_check
  ('compv_qc_v1', 'comp_quality_check', 1,
   'lakeon.components.video.quality_check',
   '{"review_mode":{"type":"string","default":"manual","enum":["manual","auto_approve"]},"thumbnail_count":{"type":"integer","default":4}}',
   '{"type":"any"}',
   '{"type":"any"}',
   NULL, FALSE, NULL, 'HUMAN_REVIEW', 'PUBLISHED', NOW()),

  -- video_labeling_mock
  ('compv_label_v1', 'comp_video_label_mock', 1,
   'lakeon.components.video.video_labeling_mock',
   '{"tasks":{"type":"array","default":["viclip_tag","caption","camera_motion"]},"viclip_top_k":{"type":"integer","default":5}}',
   '{"type":"video_clips","format":"mp4"}',
   '{"type":"labeled_clips","format":"mp4"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- dataset_publish
  ('compv_publish_v1', 'comp_dataset_publish', 1,
   'lakeon.components.universal.dataset_publish',
   '{"dataset_name":{"type":"string","default":""},"format":{"type":"string","default":"PARQUET","enum":["PARQUET","LANCE"]},"text_key":{"type":"string","default":"content"}}',
   '{"type":"any"}',
   '{"type":"dataset_version"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- text_dedup
  ('compv_dedup_v1', 'comp_text_dedup', 1,
   'lakeon.components.text.text_dedup',
   '{"method":{"type":"string","default":"minhash"},"similarity_threshold":{"type":"number","default":0.85},"num_perm":{"type":"integer","default":128},"ngram":{"type":"integer","default":3}}',
   '{"type":"text_records","format":"jsonl"}',
   '{"type":"text_records","format":"jsonl"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- text_clean
  ('compv_clean_v1', 'comp_text_clean', 1,
   'lakeon.components.text.text_clean',
   '{"remove_html":{"type":"boolean","default":true},"normalize_whitespace":{"type":"boolean","default":true},"remove_urls":{"type":"boolean","default":true},"min_length":{"type":"integer","default":50},"max_length":{"type":"integer","default":100000},"language_filter":{"type":"array","default":["zh","en"]}}',
   '{"type":"text_records","format":"jsonl"}',
   '{"type":"text_records","format":"jsonl"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- text_tokenize
  ('compv_token_v1', 'comp_text_tokenize', 1,
   'lakeon.components.text.text_tokenize',
   '{"tokenizer":{"type":"string","default":"tiktoken","enum":["tiktoken","jieba"]},"tiktoken_model":{"type":"string","default":"cl100k_base"},"compute_stats":{"type":"boolean","default":true}}',
   '{"type":"text_records","format":"jsonl"}',
   '{"type":"text_records","format":"jsonl"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- text_quality_score
  ('compv_quality_v1', 'comp_text_quality', 1,
   'lakeon.components.text.text_quality_score',
   '{"scorer":{"type":"string","default":"rule"},"min_score":{"type":"number","default":0.6}}',
   '{"type":"text_records","format":"jsonl"}',
   '{"type":"text_records","format":"jsonl"}',
   '["passed","low_quality"]', FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW());

-- ============================================================
-- 3. 预置 Pipeline 模板 (tenant_id = 'system', is_template = TRUE)
-- ============================================================

-- Video Pipeline Template
INSERT INTO pipelines (id, tenant_id, name, description, data_type, is_template, source_template_id, latest_version, created_at, updated_at)
VALUES
  ('pipe_tpl_video_clean', 'system', '视频数据清洗流水线',
   '从原始视频到清洗标注后的视频片段数据集。包含规整适配、镜头切分、规则清洗、裁剪、模型清洗(Mock)、人工质检、内容标注(Mock)、发布等步骤。',
   'VIDEO', TRUE, NULL, 1, NOW(), NOW()),

  ('pipe_tpl_text_clean', 'system', '文本数据清洗流水线',
   '从原始文本到高质量训练数据集。包含 MinHash 去重、HTML/URL 清洗、分词统计、质量评分、人工质检、发布等步骤。',
   'TEXT', TRUE, NULL, 1, NOW(), NOW());

-- Video Pipeline Template Version (DAG YAML)
INSERT INTO pipeline_versions (id, pipeline_id, version, dag_yaml, status, created_at)
VALUES
  ('pipev_tpl_video_v1', 'pipe_tpl_video_clean', 1,
   'name: 视频数据清洗流水线
data_type: VIDEO
description: 从原始视频到清洗标注后的视频片段数据集

steps:
  - id: normalize
    component: video_normalize
    component_version: 1
    params: { target_resolution: "1080p", target_format: "mp4" }
    inputs: { video: "$input.dataset" }
    outputs: { video: normalized }

  - id: scene_split
    component: video_scene_split
    component_version: 1
    params: { threshold: 27, min_scene_length: 1.0 }
    inputs: { video: normalize.video }
    fan_out: true
    checkpoint: true
    outputs: { clips: split_clips }

  - id: rule_filter
    component: rule_filter
    component_version: 1
    depends_on: [scene_split]
    params: { min_duration: 3, min_resolution: 480, max_aspect_ratio: 2, min_fps: 20, min_crop_area: 5 }
    inputs: { clip: scene_split.clips }
    output_branches: [passed, needs_crop, dropped]
    outputs: { passed: passed_clip, needs_crop: crop_clip }

  - id: crop
    component: video_crop
    component_version: 1
    condition: "rule_filter.needs_crop"
    inputs: { clip: rule_filter.crop_clip }
    outputs: { clip: cropped_clip }

  - id: merge_clean
    type: merge
    inputs: [rule_filter.passed_clip, crop.clip]
    outputs: { clips: merged_clips }

  - id: model_filter
    component: model_filter_mock
    component_version: 1
    depends_on: [merge_clean]
    params: { checks: [vqa, watermark, subtitle, optical_flow] }
    inputs: { clips: merge_clean.clips }
    checkpoint: true
    outputs: { clips: cleaned_clips }

  - id: qc
    component: quality_check
    component_version: 1
    execution_mode: HUMAN_REVIEW
    depends_on: [model_filter]
    inputs: { clips: model_filter.clips }
    outputs: { clips: approved_clips }

  - id: labeling
    component: video_labeling_mock
    component_version: 1
    depends_on: [qc]
    params: { tasks: [viclip_tag, caption, camera_motion] }
    inputs: { clips: qc.clips }
    outputs: { clips: labeled_clips }

  - id: publish
    component: dataset_publish
    component_version: 1
    depends_on: [labeling]
    inputs: { clips: labeling.clips }
    output_dataset: { name: "清洗后视频数据集", format: LANCE }',
   'PUBLISHED', NOW());

-- Text Pipeline Template Version (DAG YAML)
INSERT INTO pipeline_versions (id, pipeline_id, version, dag_yaml, status, created_at)
VALUES
  ('pipev_tpl_text_v1', 'pipe_tpl_text_clean', 1,
   'name: 文本数据清洗流水线
data_type: TEXT
description: 从原始文本到高质量训练数据集

steps:
  - id: dedup
    component: text_dedup
    component_version: 1
    params: { method: "minhash", similarity_threshold: 0.85 }
    inputs: { text: "$input.dataset" }
    checkpoint: true
    outputs: { text: deduped }

  - id: clean
    component: text_clean
    component_version: 1
    depends_on: [dedup]
    params: { remove_html: true, normalize_whitespace: true, remove_urls: true, min_length: 50, max_length: 100000, language_filter: ["zh", "en"] }
    inputs: { text: dedup.text }
    outputs: { text: cleaned }

  - id: tokenize_stats
    component: text_tokenize
    component_version: 1
    depends_on: [clean]
    params: { tokenizer: "tiktoken", compute_stats: true }
    inputs: { text: clean.text }
    checkpoint: true
    outputs: { text: tokenized }

  - id: quality_score
    component: text_quality_score
    component_version: 1
    depends_on: [tokenize_stats]
    params: { scorer: "rule", min_score: 0.6 }
    inputs: { text: tokenize_stats.text }
    output_branches: [passed, low_quality]
    outputs: { passed: good_text }

  - id: qc
    component: quality_check
    component_version: 1
    execution_mode: HUMAN_REVIEW
    depends_on: [quality_score]
    inputs: { text: quality_score.good_text }
    outputs: { text: approved_text }

  - id: publish
    component: dataset_publish
    component_version: 1
    depends_on: [qc]
    inputs: { text: qc.text }
    output_dataset: { name: "清洗后文本数据集", format: PARQUET }',
   'PUBLISHED', NOW());
