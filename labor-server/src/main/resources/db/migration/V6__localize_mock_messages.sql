UPDATE push_task
SET remote_message = '模拟推送成功'
WHERE remote_message = 'Mock 推送成功';

UPDATE integration_config
SET last_test_message = REPLACE(last_test_message, 'Mock', '模拟')
WHERE last_test_message LIKE '%Mock%';
