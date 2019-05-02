def test_cron_stopped(host):
    cron = host.service("cron")
    assert not cron.is_running
