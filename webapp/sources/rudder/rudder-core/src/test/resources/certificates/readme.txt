windows-bad-certificate.ocs  contains cert-nodeid-bad.pem    signed with  key-nodeid-bad.pem
windows-same-certificate.ocs contains cert-nodeid-ok.pem     signed with  key-nodeid-ok.pem
windows-new-certificate.ocs  contains cert-nodeid-ok-new.pem signed with *key-nodeid-ok.pem*

linux-cfe-sign-cert-two-pem.ocs   contains cert-nodeid-ok-linux.pem + cert-forged-uid-windows.pem in one AGENT_CERT   signed with cert-nodeid-ok-linux.priv
linux-cfe-sign-cert-two-tags.ocs  contains cert-nodeid-ok-linux.pem and cert-forged-uid-windows.pem in two AGENT_CERT signed with cert-nodeid-ok-linux.priv
