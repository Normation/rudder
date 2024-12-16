def test_syslogd_started(host):
    syslogd = host.service("syslogd")
    assert syslogd.is_running
