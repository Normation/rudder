{
  &SUB_NODES_NAME, SUB_NODES_ID, SUB_NODES_KEYHASH, SUB_NODES_SERVER: {host, uuid, key, server |
    "&uuid&": {
      "hostname": "&host&",
      "key-hash": "&key&",
      "policy-server": "&server&"
    }
  };separator=",
" &
}
