def test_crond_disabled(host):
    crond = host.service("crond")
    assert not crond.is_enabled
