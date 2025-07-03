module Editor.MethodConditions exposing (..)


--
-- This file model the "condition" form applied to generic methods
--

--
-- This is not an exact mapping of Rudder language OS conditions but a simplified version
-- to handle most common cases.
--
-- For example, Amazon Linux versions are not handled as they are not cleanly mapped to
-- sensible CFEngine classes.
--


type OS = AIX     { version : Maybe  Int }
        | Linux   ( Maybe LinuxOS )
        | Solaris { major : Maybe  Int, minor : Maybe Int }
        | Windows ( Maybe WindowsOS )

type alias Condition =
  { os       : Maybe OS
  , advanced : String
  }


type UbuntuMinor = All | Ten | ZeroFour

showUbuntuMinor: UbuntuMinor -> String
showUbuntuMinor v =
  case v of
    All ->  "All"
    ZeroFour -> "04"
    Ten -> "10"

-- Here Redhat and Suse are actually families of OSes
-- But we want to avoid a third level of selection so we keep them inline
type LinuxOS = DebianFamily
             | Debian    { major : Maybe  Int, minor : Maybe Int }
             | Ubuntu    { major : Maybe  Int, minor : UbuntuMinor }
             | RedhatFamily
             | RHEL      { major : Maybe  Int, minor : Maybe Int }
             | Centos    { major : Maybe  Int, minor : Maybe Int }
             | Alma      { major : Maybe  Int, minor : Maybe Int }
             | Rocky     { major : Maybe  Int, minor : Maybe Int }
             | Oracle    { major : Maybe  Int, minor : Maybe Int }
             | Fedora    { version : Maybe  Int }
             | Amazon
             | SuseFamily
             | SLES      { version : Maybe  Int, sp : Maybe Int }
             | SLED      { version : Maybe  Int, sp : Maybe Int }
             | OpenSuse  { major : Maybe  Int, minor : Maybe Int }
             | Slackware { major : Maybe  Int, minor : Maybe Int }

-- Treat all Windows versions as specific enum variants. Numbering is inconsistant
-- and trying to use integers would be misleading to users.
-- We don't have anything generic other server vs. non server, so we only have one enum
type WindowsOS = Seven | Eight | EightDotOne | WinTen | Eleven | V2008 | V2008R2 | V2012 | V2012R2 | V2016 | V2019 | V2022 | V2025
showWindowsOS: WindowsOS -> String
showWindowsOS os =
  case os of
    Seven       -> "7"
    Eight       -> "8"
    EightDotOne -> "8.1"
    WinTen      -> "10"
    Eleven      -> "11"
    V2008   -> "Server 2008"
    V2008R2 -> "Server 2008 R2"
    V2012   -> "Server 2012"
    V2012R2 -> "Server 2012 R2"
    V2016   -> "Server 2016"
    V2019   -> "Server 2019"
    V2022   -> "Server 2022"
    V2025   -> "Server 2025"

osName: Maybe OS -> String
osName maybeOs =
  case maybeOs of
    Nothing -> "All"
    Just os ->
      case os of
        AIX _              -> "AIX"
        Solaris _          -> "Solaris"
        Windows Nothing    -> "Windows family"
        Windows (Just win) -> "Windows " ++ (showWindowsOS win)
        Linux Nothing      -> "Linux"
        Linux (Just linux) ->
          case linux of
            DebianFamily  -> "Debian family"
            Debian _      -> "Debian"
            Ubuntu _      -> "Ubuntu"
            RedhatFamily  -> "Red Hat family"
            RHEL _        -> "RHEL"
            Centos _      -> "CentOS"
            Alma _        -> "AlmaLinux"
            Rocky _       -> "Rocky Linux"
            Oracle _      -> "Oracle Linux"
            Fedora _      -> "Fedora"
            Amazon        -> "Amazon Linux"
            SuseFamily    -> "SUSE family"
            SLES _        -> "SLES"
            SLED _        -> "SLED"
            OpenSuse _    -> "OpenSUSE"
            Slackware _   -> "Slackware"

majorMinorVersionCondition: String -> { major : Maybe Int, minor : Maybe Int} -> String
majorMinorVersionCondition s v =
  case (v.major, v.minor) of
    (Just major, Nothing)    -> s ++ "_" ++ (String.fromInt major)
    (Just major, Just minor) -> s ++ "_" ++ (String.fromInt major) ++ "_" ++ (String.fromInt minor)
    _                        -> s

ubuntuCondition: String -> { major : Maybe Int, minor : UbuntuMinor} -> String
ubuntuCondition s v =
  case (v.major, v.minor) of
    (Just major, All)    -> s ++ "_" ++ (String.fromInt major)
    (Just major, m) -> s ++ "_" ++ (String.fromInt major) ++ "_" ++ (showUbuntuMinor m)
    (Nothing, _) -> s

versionSPCondition: String -> { version : Maybe Int, sp : Maybe Int} -> String
versionSPCondition s v =
  case (v.version, v.sp) of
    (Just major, Nothing)    -> s ++ "_" ++ (String.fromInt major)
    (Just major, Just minor) -> s ++ "_" ++ (String.fromInt major) ++ "_" ++ (String.fromInt minor)
    _                        -> s

conditionWin: WindowsOS -> String
conditionWin os =
  case os of
    Seven       -> "windows_7"
    Eight       -> "windows_8"
    EightDotOne -> "windows_8_1"
    WinTen      -> "windows_10"
    Eleven      -> "windows_11"
    V2008   -> "windows_server_2008"
    V2008R2 -> "windows_server_2008_R2"
    V2012   -> "windows_server_2012"
    V2012R2 -> "windows_server_2012_R2"
    V2016   -> "windows_server_2016"
    V2019   -> "windows_server_2019"
    V2022   -> "windows_server_2022"
    V2025   -> "windows_server_2025"

conditionLinux: LinuxOS -> String
conditionLinux os =
  case os of
    DebianFamily  -> "debian"
    Debian v      -> majorMinorVersionCondition "debian" v
    Ubuntu v      -> ubuntuCondition "ubuntu" v
    RedhatFamily  -> "redhat"
    -- redhat_x_y are defined on Oracle Linux too,
    -- but let's keep things simple here.
    RHEL v        -> majorMinorVersionCondition "redhat" v
    Centos v      -> majorMinorVersionCondition "centos" v
    Alma v        -> majorMinorVersionCondition "almalinux" v
    Rocky v       -> majorMinorVersionCondition "rocky" v
    Oracle v      -> majorMinorVersionCondition "oracle" v
    Fedora v      -> Maybe.withDefault "fedora" (Maybe.map (String.fromInt >> (++) "fedora_") v.version)
    Amazon        -> "amazon_linux"
    SuseFamily    -> "suse"
    SLES v        -> versionSPCondition "sles" v
    SLED v        -> versionSPCondition "sled" v
    OpenSuse v    -> majorMinorVersionCondition "opensuse" v
    Slackware v   -> majorMinorVersionCondition "slackware" v

conditionOs : OS -> String
conditionOs os =
  case os of
    AIX v                -> Maybe.withDefault "aix" (Maybe.map (String.fromInt >> (++) "aix_") v.version)
    Linux Nothing        -> "linux"
    Solaris v            -> majorMinorVersionCondition "solaris" v
    Windows Nothing      -> "windows"
    Windows (Just winOs) -> conditionWin winOs
    Linux (Just linuxOs) -> conditionLinux linuxOs

conditionStr : Condition -> String
conditionStr condition =
  case condition.os of
    Nothing -> condition.advanced
    Just os ->
      if (String.isEmpty condition.advanced) then conditionOs os else conditionOs os ++ "." ++ condition.advanced

parseOs: String -> Maybe OS
parseOs os =
  case String.split "_" os of
    [ "windows" ]                         -> Just (Windows Nothing)
    [ "windows", "server", "2008" ]       -> Just (Windows (Just V2008))
    [ "windows", "server", "2008", "R2" ] -> Just (Windows (Just V2008R2))
    [ "windows", "server", "2012" ]       -> Just (Windows (Just V2012))
    [ "windows", "server", "2012", "R2" ] -> Just (Windows (Just V2012R2))
    [ "windows", "server", "2016" ]       -> Just (Windows (Just V2016))
    [ "windows", "server", "2019" ]       -> Just (Windows (Just V2019))
    [ "windows", "server", "2022" ]       -> Just (Windows (Just V2022))
    [ "windows", "7" ]      -> Just (Windows (Just Seven))
    [ "windows", "8" ]      -> Just (Windows (Just Eight))
    [ "windows", "8", "1" ] -> Just (Windows (Just EightDotOne))
    [ "windows", "10" ]     -> Just (Windows (Just WinTen))
    [ "windows", "11" ]     -> Just (Windows (Just Eleven))
    
    [ "linux" ]        -> Just (Linux Nothing)
    [ "suse" ]         -> Just (Linux (Just (SuseFamily)))
    [ "redhat" ]       -> Just (Linux (Just (RedhatFamily)))
    [ "debian" ]       -> Just (Linux (Just (DebianFamily)))
    [ "amazon_linux" ] -> Just (Linux (Just (Amazon)))

    [ "fedora" ]        -> Just (Linux (Just (Fedora  { version = Nothing })))
    [ "fedora", major ] -> case String.toInt major of
                             Nothing -> Nothing
                             x       -> Just (Linux (Just (Fedora { version = x })))

    [ "redhat", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (RHEL { major = x, minor = Nothing })))
    [ "redhat", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                    (Nothing, Nothing) -> Nothing
                                    (Just _, Nothing)  -> Nothing
                                    (Nothing, Just _ ) -> Nothing
                                    (x, y)             -> Just (Linux (Just (RHEL { major = x, minor = y })))

    [ "centos" ]               -> Just (Linux (Just (Centos { major = Nothing, minor = Nothing })))
    [ "centos", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (Centos { major = x, minor = Nothing })))
    [ "centos", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                    (Nothing, Nothing) -> Nothing
                                    (Just _, Nothing)  -> Nothing
                                    (Nothing, Just _ ) -> Nothing
                                    (x, y)             -> Just (Linux (Just (Centos { major = x, minor = y })))

    [ "almalinux" ]               -> Just (Linux (Just (Alma { major = Nothing, minor = Nothing })))
    [ "almalinux", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (Alma { major = x, minor = Nothing })))
    [ "almalinux", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                    (Nothing, Nothing) -> Nothing
                                    (Just _, Nothing)  -> Nothing
                                    (Nothing, Just _ ) -> Nothing
                                    (x, y)             -> Just (Linux (Just (Alma { major = x, minor = y })))

    [ "rocky" ]               -> Just (Linux (Just (Rocky { major = Nothing, minor = Nothing })))
    [ "rocky", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (Rocky { major = x, minor = Nothing })))
    [ "rocky", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                    (Nothing, Nothing) -> Nothing
                                    (Just _, Nothing)  -> Nothing
                                    (Nothing, Just _ ) -> Nothing
                                    (x, y)             -> Just (Linux (Just (Rocky { major = x, minor = y })))

    [ "oracle" ]               -> Just (Linux (Just (Oracle { major = Nothing, minor = Nothing })))
    [ "oracle", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (Oracle { major = x, minor = Nothing })))
    [ "oracle", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                    (Nothing, Nothing) -> Nothing
                                    (Just _, Nothing)  -> Nothing
                                    (Nothing, Just _ ) -> Nothing
                                    (x, y)             -> Just (Linux (Just (Oracle { major = x, minor = y })))

    [ "debian", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (Debian { major = x, minor = Nothing })))
    [ "debian", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                    (Nothing, Nothing) -> Nothing
                                    (Just _, Nothing)  -> Nothing
                                    (Nothing, Just _ ) -> Nothing
                                    (x, y)             -> Just (Linux (Just (Debian { major = x, minor = y })))

    [ "ubuntu" ]               -> Just (Linux (Just (Ubuntu { major = Nothing, minor = All })))
    [ "ubuntu", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (Ubuntu { major = x, minor = All })))
    [ "ubuntu", major, minor ] -> case (String.toInt major,  minor) of
                                    (Nothing, _) -> Nothing
                                    (Just x, "04")  -> Just (Linux (Just (Ubuntu { major = Just x, minor = ZeroFour })))
                                    (Just x, "10")  -> Just (Linux (Just (Ubuntu { major = Just x, minor = Ten })))
                                    (Just _, _ ) -> Nothing

    [ "opensuse" ]               -> Just (Linux (Just (OpenSuse { major = Nothing, minor = Nothing })))
    [ "opensuse", major ]        -> case String.toInt major of
                                      Nothing -> Nothing
                                      x       -> Just (Linux (Just (OpenSuse { major = x, minor = Nothing })))
    [ "opensuse", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                      (Nothing, Nothing) -> Nothing
                                      (Just _, Nothing)  -> Nothing
                                      (Nothing, Just _ ) -> Nothing
                                      (x, y)             -> Just (Linux (Just (OpenSuse { major = x, minor = y })))

    [ "sles" ]               -> Just (Linux (Just (SLES { version = Nothing, sp = Nothing })))
    [ "sles", major ]        -> case String.toInt major of
                                  Nothing -> Nothing
                                  x       -> Just (Linux (Just (SLES { version = x, sp = Nothing })))
    [ "sles", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                  (Nothing, Nothing) -> Nothing
                                  (Just _, Nothing)  -> Nothing
                                  (Nothing, Just _ ) -> Nothing
                                  (x, y)             -> Just (Linux (Just (SLES { version = x, sp = y })))

    [ "sled" ]               -> Just (Linux (Just (SLED { version = Nothing, sp = Nothing })))
    [ "sled", major ]        -> case String.toInt major of
                                  Nothing -> Nothing
                                  x       -> Just (Linux (Just (SLED { version = x, sp = Nothing })))
    [ "sled", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                  (Nothing, Nothing) -> Nothing
                                  (Just _, Nothing)  -> Nothing
                                  (Nothing, Just _ ) -> Nothing
                                  (x, y)             -> Just (Linux (Just (SLED { version = x, sp = y })))

    [ "slackware" ]               -> Just (Linux (Just (Slackware { major = Nothing, minor = Nothing })))
    [ "slackware", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (Slackware { major = x, minor = Nothing })))
    [ "slackware", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                       (Nothing, Nothing) -> Nothing
                                       (Just _, Nothing)  -> Nothing
                                       (Nothing, Just _ ) -> Nothing
                                       (x, y)             -> Just (Linux (Just (Slackware { major = x, minor = y })))

    -- end linux distrib, other unixes --

    [ "solaris" ]               -> Just (Solaris { major = Nothing, minor = Nothing })
    [ "solaris", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Solaris { major = x, minor = Nothing })
    [ "solaris", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                     (Nothing, Nothing) -> Nothing
                                     (Just _, Nothing)  -> Nothing
                                     (Nothing, Just _ ) -> Nothing
                                     (x, y)             -> Just (Solaris { major = x, minor = y })

    [ "aix" ]        -> Just (AIX  { version = Nothing })
    [ "aix", major ] -> case String.toInt major of
                          Nothing -> Nothing
                          x       -> Just (AIX { version = x })
    _ -> Nothing


noVersion = {major = Nothing, minor = Nothing }

osList : List (Maybe OS)
osList =
  [ Nothing
  , Just (Linux Nothing)
  , Just (Linux (Just (DebianFamily)))
  , Just (Linux (Just (Debian noVersion)))
  , Just (Linux (Just (Ubuntu {major = Nothing, minor = All })))
  , Just (Linux (Just (RedhatFamily)))
  , Just (Linux (Just (RHEL noVersion)))
  , Just (Linux (Just (Centos noVersion)))
  , Just (Linux (Just (Alma noVersion)))
  , Just (Linux (Just (Rocky noVersion)))
  , Just (Linux (Just (Oracle noVersion)))
  , Just (Linux (Just (Fedora {version = Nothing})))
  , Just (Linux (Just (Amazon)))
  , Just (Linux (Just (SuseFamily)))
  , Just (Linux (Just (SLES {version = Nothing, sp = Nothing})))
  , Just (Linux (Just (SLED {version = Nothing, sp = Nothing})))
  , Just (Linux (Just (OpenSuse noVersion)))
  , Just (Linux (Just (Slackware noVersion)))
  , Just (Windows Nothing)
  , Just (Windows (Just V2008))
  , Just (Windows (Just V2008R2))
  , Just (Windows (Just V2012))
  , Just (Windows (Just V2012R2))
  , Just (Windows (Just V2016))
  , Just (Windows (Just V2019))
  , Just (Windows (Just V2022))
  , Just (Windows (Just V2025))
  , Just (Windows (Just Seven))
  , Just (Windows (Just Eight))
  , Just (Windows (Just EightDotOne))
  , Just (Windows (Just WinTen))
  , Just (Windows (Just Eleven))
  , Just ( AIX {version = Nothing} )
  , Just ( Solaris noVersion)
  ]

-- VERSION in the condition part --
-- some OS only have one version (+maybe service packs). Deals with it here
hasVersion: Maybe OS -> Bool
hasVersion os =
  case os of
    Just (Linux (Just (Fedora _))) -> True
    Just (Linux (Just (SLES _))) -> True
    Just (Linux (Just (SLED _))) -> True
    Just (AIX _) -> True
    _ -> False

getVersion: Maybe OS -> Maybe Int
getVersion os =
  case os of
    Just (Linux (Just (Fedora v))) -> v.version
    Just (Linux (Just (SLES v))) -> v.version
    Just (Linux (Just (SLED v))) -> v.version
    Just (AIX v) -> v.version
    _ -> Nothing

updateVersion:  Maybe Int -> Maybe OS -> Maybe OS
updateVersion newVersion os =
  case os of
    Just (Linux (Just (SLES v))) -> Just (Linux (Just (SLES {v | version = newVersion})))
    Just (Linux (Just (SLED v))) -> Just (Linux (Just (SLED {v | version = newVersion})))
    Just (Linux (Just (Fedora v))) -> Just (Linux (Just (Fedora {v | version = newVersion})))
    Just (AIX v) -> Just (AIX {v | version = newVersion})
    _ -> os

-- for OS with service packs
hasSP: Maybe OS -> Bool
hasSP os =
  case os of
    Just (Linux (Just (SLES _))) -> True
    Just (Linux (Just (SLED _))) -> True
    _ -> False

getSP: Maybe OS -> Maybe Int
getSP os =
  case os of
    Just (Linux (Just (SLES v))) -> v.sp
    Just (Linux (Just (SLED v))) -> v.sp
    _ -> Nothing

updateSP:  Maybe Int -> Maybe OS -> Maybe OS
updateSP newSP os =
  case os of
    Just (Linux (Just (SLES v))) -> Just (Linux (Just (SLES {v | sp = newSP})))
    Just (Linux (Just (SLED v))) -> Just (Linux (Just (SLED {v | sp = newSP})))
    _ -> os

-- most OS have a major+minor version

isUbuntu: Maybe OS -> Bool
isUbuntu os =
  case os of
    Just (Linux (Just (Ubuntu _))) -> True
    _ -> False

hasMajorMinorVersion: Maybe OS -> Bool
hasMajorMinorVersion os =
  case os of
    Just (Linux (Just (Debian _))) -> True
    Just (Linux (Just (RHEL _))) -> True
    Just (Linux (Just (Centos _))) -> True
    Just (Linux (Just (Alma _))) -> True
    Just (Linux (Just (Rocky _))) -> True
    Just (Linux (Just (Oracle _))) -> True
    Just (Linux (Just (OpenSuse _))) -> True
    Just (Linux (Just (Slackware _))) -> True
    Just (Solaris _) -> True
    _ -> False


getMajorVersion:  Maybe OS -> Maybe Int
getMajorVersion os =
  case os of
    Just (Linux (Just (Debian v))) -> v.major
    Just (Linux (Just (Ubuntu v)))-> v.major
    Just (Linux (Just (RHEL v))) -> v.major
    Just (Linux (Just (Centos v))) -> v.major
    Just (Linux (Just (Alma v))) -> v.major
    Just (Linux (Just (Rocky v))) -> v.major
    Just (Linux (Just (Oracle v))) -> v.major
    Just (Linux (Just (OpenSuse v))) -> v.major
    Just (Linux (Just (Slackware v))) -> v.major
    Just (Solaris v) -> v.major
    _ -> Nothing

getUbuntuMinor:  Maybe OS -> String
getUbuntuMinor  os =
  case os of
    Just (Linux (Just (Ubuntu v))) -> showUbuntuMinor v.minor

    _ -> ""

getMinorVersion:  Maybe OS -> Maybe Int
getMinorVersion os =
  case os of
    Just (Linux (Just (Debian v))) -> v.minor
    Just (Linux (Just (RHEL v))) -> v.minor
    Just (Linux (Just (Centos v))) -> v.minor
    Just (Linux (Just (Alma v))) -> v.minor
    Just (Linux (Just (Rocky v))) -> v.minor
    Just (Linux (Just (Oracle v))) -> v.minor
    Just (Linux (Just (OpenSuse v))) -> v.minor
    Just (Linux (Just (Slackware v))) -> v.minor
    Just (Solaris v) -> v.minor
    _ -> Nothing

updateMajorVersion: Maybe Int -> Maybe OS -> Maybe OS
updateMajorVersion newMajor os =
  case os of
    Just (Linux (Just (Debian v))) -> Just (Linux (Just (Debian {v | major = newMajor})))
    Just (Linux (Just (Ubuntu v)))-> Just (Linux (Just (Ubuntu {v | major = newMajor})))
    Just (Linux (Just (RHEL v))) -> Just (Linux (Just (RHEL {v | major = newMajor})))
    Just (Linux (Just (Centos v))) -> Just (Linux (Just (Centos {v | major = newMajor})))
    Just (Linux (Just (Alma v))) -> Just (Linux (Just (Alma {v | major = newMajor})))
    Just (Linux (Just (Rocky v))) -> Just (Linux (Just (Rocky {v | major = newMajor})))
    Just (Linux (Just (Oracle v))) -> Just (Linux (Just (Oracle {v | major = newMajor})))
    Just (Linux (Just (OpenSuse v))) -> Just (Linux (Just (OpenSuse {v | major = newMajor})))
    Just (Linux (Just (Slackware v))) -> Just (Linux (Just (Slackware {v | major = newMajor})))
    Just (Solaris v) -> Just ( Solaris { v | major = newMajor } )
    _ -> os

updateUbuntuMinor: UbuntuMinor -> Maybe OS -> Maybe OS
updateUbuntuMinor newUbuntuMinor os =
  case os of
    Just (Linux (Just (Ubuntu v))) -> Just (Linux (Just (Ubuntu {v | minor = newUbuntuMinor})))
    _ -> os

updateMinorVersion: Maybe Int -> Maybe OS -> Maybe OS
updateMinorVersion newMinor os =
  case os of
    Just (Linux (Just (Debian v))) -> Just (Linux (Just (Debian {v | minor = newMinor})))
    Just (Linux (Just (Ubuntu v))) -> case newMinor of
                                        Just 4 -> Just (Linux (Just (Ubuntu {v | minor = ZeroFour})))
                                        Just 10 -> Just (Linux (Just (Ubuntu {v | minor = Ten})))
                                        _ -> Just (Linux (Just (Ubuntu {v | minor = All})))
    Just (Linux (Just (RHEL v))) -> Just (Linux (Just (RHEL {v | minor = newMinor})))
    Just (Linux (Just (Centos v))) -> Just (Linux (Just (Centos {v | minor = newMinor})))
    Just (Linux (Just (Alma v))) -> Just (Linux (Just (Alma {v | minor = newMinor})))
    Just (Linux (Just (Rocky v))) -> Just (Linux (Just (Rocky {v | minor = newMinor})))
    Just (Linux (Just (Oracle v))) -> Just (Linux (Just (Oracle {v | minor = newMinor})))
    Just (Linux (Just (OpenSuse v))) -> Just (Linux (Just (OpenSuse {v | minor = newMinor})))
    Just (Linux (Just (Slackware v))) -> Just (Linux (Just (Slackware {v | minor = newMinor})))
    Just (Solaris v) -> Just ( Solaris { v | minor = newMinor } )
    _ -> os


osClass: Maybe OS -> String
osClass maybeOs =
  case maybeOs of
    Nothing -> "optGroup"
    Just os ->
      case os of
        AIX _ -> "optGroup"
        Solaris _ -> "optGroup"
        Windows Nothing -> "optGroup"
        Windows (Just _) -> "optChild"
        Linux Nothing -> "optGroup"
        Linux (Just _) -> "optChild"
