def test_cron_enabled(host):
    cron = host.service("cron")
    assert cron.is_enabled
