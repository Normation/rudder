# Test files

Certificates creation:

```
openssl genrsa -out CA.key 2048
openssl req -x509 -new -nodes -key CA.key -sha256 -days 1825 -out CA.pem

openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr
openssl x509 -req -in server.csr -CA CA.pem -CAkey CA.key -CAcreateserial -out server.crt -days 825 -sha256 -extfile server.ext -subj "/UID=root

openssl genrsa -out agent1.key 2048
openssl req -new -key agent1.key -out agent1.csr
openssl x509 -req -in agent1.csr -CA CA.pem -CAkey CA.key -CAcreateserial -out agent1.pem -days 825 -sha256 -extfile agent1.ext -subj "/UID=597afba8-04ac-445f-b195-6eae3d45ae11"
```

With `agent1.ext` containing:

```
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = agent2
```

File signature:

```
openssl smime -sign -signer agent1.pem -in file -out file.signed -inkey agent1.key -nocerts
```
