def test_crond_stopped(host):
    crond = host.service("crond")
    assert not crond.is_running
