def test_ntp_enabled(host):
    ntp = host.service("ntp")
    assert ntp.is_enabled
