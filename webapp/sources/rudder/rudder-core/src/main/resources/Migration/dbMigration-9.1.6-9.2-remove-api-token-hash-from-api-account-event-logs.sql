/*
 *************************************************************************************
 * Copyright 2026 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

-- CreateAPIAccount & DeleteAPIAccount event logs : remove the token node
UPDATE eventlog
SET data=(regexp_replace(
        data::text,
        '^<entry>(<apiAccount.*><id>.+</id><name>.*</name>)<token>.*</token>',
        '<entry>\1'))::xml
WHERE
    (eventtype = 'CreateAPIAccount' OR eventtype = 'DeleteAPIAccount')

-- ModifyAPIAccount event logs : hide content of token/from node if it is not empty
UPDATE eventlog
SET data=(regexp_replace(
        data::text,
        '^<entry>(<apiAccount.*><id>.+</id>)(<name>.*</name>)?<token><from>.+</from>(<to>.*</to>|<to/>)</token>',
        '<entry>\1\2<token><from>[REDACTED ApiTokenHash]</from>\3</token>'))::xml
WHERE
    (eventtype = 'ModifyAPIAccount')
    AND ((xpath('/entry/apiAccount/token/from/text()', data))[1]::text != '')

-- ModifyAPIAccount event logs : hide content of token/to node if it is not empty
UPDATE eventlog
SET data=(regexp_replace(
        data::text,
        '^<entry>(<apiAccount.*><id>.+</id>)(<name>.*</name>)?<token>(<from>.*</from>|<from/>)<to>.+</to></token>',
        '<entry>\1\2<token>\3<to>[REDACTED ApiTokenHash]</to></token>'))::xml
WHERE
    (eventtype = 'ModifyAPIAccount')
    AND ((xpath('/entry/apiAccount/token/to/text()', data))[1]::text != '')
