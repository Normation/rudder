def test_cron_disabled(host):
    cron = host.service("cron")
    assert not cron.is_enabled
