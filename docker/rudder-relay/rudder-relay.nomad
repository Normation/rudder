# Minimal example configuration

job "rudder" {
  datacenters = ["dc1"]
  type = "service"
  group "cache" {
    network {
      port "https" {
        static = 443
      }
      port "cfengine" {
        static = 5309
      }
    }

    service {
      name = "rudder-relay"
      tags = ["rudder"]
      port = "https"

      check {
        name     = "alive"
        type     = "tcp"
        interval = "10s"
        timeout  = "2s"
      }
    }

    task "rudder-relay" {
      driver = "docker"

      env {
        RUDDER_RELAY_SERVER = "192.168.41.2"
      }

      config {
        # latest version
        image = "amousset/rudder-relay:test1"
        ports = ["https", "cfengine"]

        hostname = "rudder-relay-01"

        #readonly_rootfs = "true"

        volumes = [
            "rudder-relay-data:/var/rudder/cfengine-community/ppkeys/"
        ]
      }

      resources {
        cpu    = 500 # 500 MHz
        memory = 256 # 256MB
      }
    }
  }
}
