UPDATE integration_call_log
SET request_summary_json = JSON_OBJECT(
    'redacted', TRUE,
    'reason', '日志脱敏规则升级，历史请求摘要已清除'
)
WHERE request_summary_json IS NOT NULL;
