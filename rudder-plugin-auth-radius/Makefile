#
# make all will build auth-radius.rpkg. 
# make demo will build a license limited version of the plugin
#

RUDDER_BRANCH = $(shell sed -ne '/^rudder-branch=/s/rudder-branch=//p' build.conf)
PLUGIN_BRANCH = $(shell sed -ne '/^plugin-branch=/s/plugin-branch=//p' build.conf)
VERSION = $(RUDDER_BRANCH)-$(PLUGIN_BRANCH)
FULL_NAME = $(shell sed -ne '/^plugin-id=/s/plugin-id=//p' build.conf)
NAME = $(shell echo $(FULL_NAME) | sed -ne 's/rudder-plugin-//p')
MAVEN_OPTS = --batch-mode -U

## for demo
# standard destination path for the license file is in module directory, "license.sign"
TARGET_LICENSE_PATH = /opt/rudder/share/plugins/$(NAME)/license
# standard destination path for the key is at JAR root, name: license.pubkey
TARGET_KEY_CLASSPATH = license.pubkey
# SIGNED_LICENSE_PATH: path towards the license file to embed
# PUBLIC_KEY_PATH: path towards the public key to embed

# build the default oss version of the package
all: std-files $(FULL_NAME)-$(VERSION).rpkg

# build a "demo" version of the plugin, limited by a license file and verified by a public key
demo: demo-files $(FULL_NAME)-$(VERSION).rpkg

clean:
	rm -f scripts.txz files.txz $(FULL_NAME)-$(VERSION).rpkg
	rm -rf target $(NAME)

scripts.txz:
	tar cJ -C packaging -f scripts.txz postinst

$(FULL_NAME)-$(VERSION).rpkg: 
	ar r $(FULL_NAME)-$(VERSION).rpkg target/metadata files.txz scripts.txz

target/metadata:
	mvn $(MAVEN_OPTS) $(DEMO) -Dcommit-id=$$(git rev-parse HEAD 2>/dev/null || true) properties:read-project-properties resources:copy-resources@copy-metadata

std-files: common-files std-jar prepare-files files.txz

demo-files: common-files check-demo demo-jar prepare-files add-license files.txz

common-files: target/metadata scripts.txz

check-demo: 
	test -n "$(SIGNED_LICENSE_PATH)"  # $$SIGNED_LICENSE_PATH must be defined
	test -n "$(PUBLIC_KEY_PATH)"      # $$PUBLIC_KEY_PATH must be defined

files.txz: 
	tar cJ -f files.txz $(NAME)

prepare-files:
	mkdir -p $(NAME)
	#cp ./src/main/resources/datasources-schema.sql $(NAME)/
	cp target/$(NAME).jar $(NAME)/

add-license:
	# embed license file since we are in demo limited build
	cp $(SIGNED_LICENSE_PATH) $(NAME)/license

std-jar:
	mvn $(MAVEN_OPTS) package
	mv target/datasources-*-plugin-with-own-dependencies.jar target/$(NAME).jar

demo-jar:
	mvn $(MAVEN_OPTS) -Dlimited -Dplugin-resource-publickey=$(TARGET_KEY_CLASSPATH) -Dplugin-resource-license=$(TARGET_LICENSE_PATH) -Dplugin-declared-version=$(VERSION) package
	mv target/datasources-*-plugin-with-own-dependencies.jar target/$(NAME).jar
	cp $(PUBLIC_KEY_PATH) target/$(TARGET_KEY_CLASSPATH)
	jar -uf target/$(NAME).jar -C target $(TARGET_KEY_CLASSPATH)

