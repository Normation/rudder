def test_syslogd_stopped(host):
    syslogd = host.service("syslogd")
    assert not syslogd.is_running
