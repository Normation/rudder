def test_crond_started(host):
    crond = host.service("crond")
    assert crond.is_running
