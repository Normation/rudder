def test_cron_started(host):
    cron = host.service("cron")
    assert cron.is_running
