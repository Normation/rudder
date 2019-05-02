def test_ntp_disabled(host):
    ntp = host.service("ntp")
    assert not ntp.is_enabled
