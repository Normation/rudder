These files originally come from relayd etst files.

* server has id: `37817c4d-fbf7-4850-a985-50021f4e8f41`
* agent has id: `e745a140-40bc-4b86-b6dc-084488fc906b`

Server cert hash pinned hash:

* `ZE9q37dB6Nq+ZJz1cdrfdt+qPL+Xk8sKkLDMTp4QemY=`
* computed with:

```
openssl x509 -in server.cert -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
```
