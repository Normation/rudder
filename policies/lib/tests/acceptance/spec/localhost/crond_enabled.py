def test_crond_enabled(host):
    crond = host.service("crond")
    assert crond.is_enabled
