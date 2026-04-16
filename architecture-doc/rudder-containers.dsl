workspace "Name" "Description"


    model {
        archetypes {
            webapp = container
            database = container
            relayd = container
            slapd = container
            git = container
            agent = softwareSystem {
                tags agent
            }
        }

        !identifiers hierarchical

        group "Sub network" {
            a1 = agent "Agent 1" {
                tags agent
            }

            a2 = agent "Agent 2"{
                tags agent
            }
            a3 = agent "Agent 3"{
                tags agent
            }
            relay = agent "Relay" {
                tags agent
            }
        }

        ss = softwareSystem "Rudder Server" {
            wa = webapp "Jetty"
            db = database "Postgresql" {
                tags "database"
            }
            ap = container "Apache"
            sre = relayd "relayd"
            sd = slapd "slapd"
            git = git "Git configuration"
        }

        a1 -> relay "Sends reports, inventories and download policies"
        a2 -> relay "Sends reports, inventories and download policies"
        a3 -> relay "Sends reports, inventories and download policies"
        relay -> ss "Sends reports, inventories and download policies"
        relay -> ss.ap "Sends reports, inventories and download policies"

        ss.wa -> ss.db
        ss.wa -> ss.sd
        ss.wa -> ss.sre
        ss.wa -> ss.git
        ss.ap -> ss.wa
    }

    views {
        styles {
            element "Group:subnetwork" {
                color #ff0000
            }
            element "database" {
                shape cylinder
            }
            element "agent" {
                shape circle
                width 200
                height 200
            }
        }

        systemLandscape {
            include *
            autolayout lr
        }

        container ss {
            include *
        }


    }

}
