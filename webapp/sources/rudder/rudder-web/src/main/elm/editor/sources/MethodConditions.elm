module MethodConditions exposing (..)


--
-- This file model the "condition" form applied to generic methods
--

type OS = AIX     { version : Maybe  Int }
        | Linux   ( Maybe LinuxOS )
        | Solaris { major : Maybe  Int, minor : Maybe Int }
        | Windows

type alias Condition =
  { os       : Maybe OS
  , advanced : String
  }

type LinuxOS = Debian    { major : Maybe  Int, minor : Maybe Int }
             | Ubuntu    { major : Maybe  Int, minor : Maybe Int }
             | RH        { major : Maybe  Int, minor : Maybe Int }
             | Centos    { major : Maybe  Int, minor : Maybe Int }
             | Fedora    { version : Maybe  Int }
             | Oracle
             | Amazon
             | Suse
             | SLES      { version : Maybe  Int, sp : Maybe Int }
             | SLED      { version : Maybe  Int, sp : Maybe Int }
             | OpenSuse  { major : Maybe  Int, minor : Maybe Int }
             | Slackware { major : Maybe  Int, minor : Maybe Int }


osName: Maybe OS -> String
osName maybeOs =
  case maybeOs of
    Nothing -> "All"
    Just os ->
      case os of
        AIX _         -> "AIX"
        Solaris _     -> "Solaris"
        Windows       -> "Windows"
        Linux Nothing -> "Linux"
        Linux (Just linux) ->
          case linux of
            Debian _    -> "Debian (and derivatives)"
            Ubuntu _    -> "Ubuntu"
            RH _        -> "Red hat (and derivatives)"
            Centos _    -> "CentOS"
            Fedora _    -> "Fedora"
            Oracle      -> "Oracle Linux"
            Amazon      -> "Amazon Linux"
            Suse        -> "SuSE family"
            SLES _      -> "SLES"
            SLED _      -> "SLED"
            OpenSuse _  -> "OpenSuSE"
            Slackware _ -> "Slackware"

majorMinorVersionCondition: String -> { major : Maybe Int, minor : Maybe Int} -> String
majorMinorVersionCondition s v =
  case (v.major, v.minor) of
    (Just major, Nothing)    -> s ++ "_" ++ (String.fromInt major)
    (Just major, Just minor) -> s ++ "_" ++ (String.fromInt major) ++ "_" ++ (String.fromInt minor)
    _                        -> s


versionSPCondition: String -> { version : Maybe Int, sp : Maybe Int} -> String
versionSPCondition s v =
  case (v.version, v.sp) of
    (Just major, Nothing)    -> s ++ "_" ++ (String.fromInt major)
    (Just major, Just minor) -> s ++ "_" ++ (String.fromInt major) ++ "_" ++ (String.fromInt minor)
    _                        -> s

conditionLinux: LinuxOS -> String
conditionLinux os =
  case os of
    Debian v    -> majorMinorVersionCondition "debian" v
    Ubuntu v    -> majorMinorVersionCondition "ubuntu" v
    RH v        -> majorMinorVersionCondition "redhat" v
    Centos v    -> majorMinorVersionCondition "centos" v
    Fedora v    -> Maybe.withDefault "fedora" (Maybe.map (String.fromInt >> (++) "fedora_") v.version)
    Oracle      -> "oracle_linux"
    Amazon      -> "amazon_linux"
    Suse        -> "suse"
    SLES v      -> versionSPCondition "sles" v
    SLED v      -> versionSPCondition "sled" v
    OpenSuse v  -> majorMinorVersionCondition "opensuse" v
    Slackware v -> majorMinorVersionCondition "slackware" v

conditionOs : OS -> String
conditionOs os =
  case os of
    AIX v                -> Maybe.withDefault "aix" (Maybe.map (String.fromInt >> (++) "aix_") v.version)
    Linux Nothing        -> "linux"
    Solaris v            -> majorMinorVersionCondition "solaris" v
    Windows              -> "windows"
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
    [ "windows" ]      -> Just Windows
    [ "linux" ]        -> Just (Linux Nothing)
    [ "suse" ]         -> Just (Linux (Just (Suse)))
    [ "oracle_linux" ] -> Just (Linux (Just (Oracle)))
    [ "amazon_linux" ] -> Just (Linux (Just (Amazon)))

    [ "fedora" ]        -> Just (Linux (Just (Fedora  { version = Nothing })))
    [ "fedora", major ] -> case String.toInt major of
                             Nothing -> Nothing
                             x       -> Just (Linux (Just (Fedora { version = x })))

    [ "redhat" ]               -> Just (Linux (Just (RH { major = Nothing, minor = Nothing })))
    [ "redhat", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (RH { major = x, minor = Nothing })))
    [ "redhat", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                    (Nothing, Nothing) -> Nothing
                                    (Just _, Nothing)  -> Nothing
                                    (Nothing, Just _ ) -> Nothing
                                    (x, y)             -> Just (Linux (Just (RH { major = x, minor = y })))

    [ "centos" ]               -> Just (Linux (Just (Centos { major = Nothing, minor = Nothing })))
    [ "centos", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (Centos { major = x, minor = Nothing })))
    [ "centos", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                    (Nothing, Nothing) -> Nothing
                                    (Just _, Nothing)  -> Nothing
                                    (Nothing, Just _ ) -> Nothing
                                    (x, y)             -> Just (Linux (Just (Centos { major = x, minor = y })))

    [ "debian" ]               -> Just (Linux (Just (Debian { major = Nothing, minor = Nothing })))
    [ "debian", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (Debian { major = x, minor = Nothing })))
    [ "debian", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                    (Nothing, Nothing) -> Nothing
                                    (Just _, Nothing)  -> Nothing
                                    (Nothing, Just _ ) -> Nothing
                                    (x, y)             -> Just (Linux (Just (Debian { major = x, minor = y })))

    [ "ubuntu" ]               -> Just (Linux (Just (Ubuntu { major = Nothing, minor = Nothing })))
    [ "ubuntu", major ]        -> case String.toInt major of
                                    Nothing -> Nothing
                                    x       -> Just (Linux (Just (Ubuntu { major = x, minor = Nothing })))
    [ "ubuntu", major, minor ] -> case (String.toInt major, String.toInt minor) of
                                    (Nothing, Nothing) -> Nothing
                                    (Just _, Nothing)  -> Nothing
                                    (Nothing, Just _ ) -> Nothing
                                    (x, y)             -> Just (Linux (Just (Ubuntu { major = x, minor = y })))

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
