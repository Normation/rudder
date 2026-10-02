module FileManager.Tests.ModelTest exposing (..)

import Expect
import FileManager.Model exposing (UploadStatus(..), currentUpload, currentUploadsInProgress, newUpload, nextUpload)
import Http exposing (Progress(..))
import List.Nonempty as NonEmptyList
import Test exposing (describe, test)


noUpload : UploadStatus String
noUpload =
    NoUpload


emptyProgress : Http.Progress
emptyProgress =
    Http.Sending { sent = 0, size = 0 }


queue : List String -> UploadStatus String
queue files =
    case NonEmptyList.fromList files of
        Nothing ->
            NoUpload

        Just nonEmpty ->
            newUpload nonEmpty


suite =
    describe "FileManager.Model"
        [ describe "nextUpload"
            [ test "should stay without upload when there is none" <|
                \_ ->
                    noUpload
                        |> nextUpload
                        |> Expect.equal noUpload
            , test "should stop the upload when the last file was the one being uploaded" <|
                \_ ->
                    queue [ "a" ]
                        |> nextUpload
                        |> Expect.equal NoUpload
            , test "should drop the file being uploaded when others are queued" <|
                \_ ->
                    queue [ "a", "b", "c" ]
                        |> nextUpload
                        |> Expect.equal (queue [ "b", "c" ])
            , test "should upload files in order, one at a time, until the queue is empty" <|
                \_ ->
                    queue [ "a", "b", "c" ]
                        |> nextUpload
                        |> nextUpload
                        |> nextUpload
                        |> Expect.equal NoUpload
            , test "should reset progress of the next upload" <|
                \_ ->
                    queue [ "a", "b" ]
                        |> nextUpload
                        |> Expect.equal (queue [ "b" ])
            ]
        , describe "currentUpload"
            [ test "should have no file to upload when there is no upload" <|
                \_ ->
                    noUpload
                        |> currentUpload
                        |> Expect.equal Nothing
            , test "should process the first file of the queue" <|
                \_ ->
                    queue [ "a", "b", "c" ]
                        |> currentUpload
                        |> Expect.equal (Just "a")
            , test "should process the following file once the first one is uploaded" <|
                \_ ->
                    queue [ "a", "b", "c" ]
                        |> nextUpload
                        |> currentUpload
                        |> Expect.equal (Just "b")
            ]
        , describe "currentUploadsInProgress"
            [ test "should be empty when there is no upload" <|
                \_ ->
                    noUpload
                        |> currentUploadsInProgress
                        |> Expect.equal []
            , test "should have progress for current upload only in last position" <|
                \_ ->
                    queue [ "a", "b", "c" ]
                        |> currentUploadsInProgress
                        |> Expect.equal [ Nothing, Nothing, Just emptyProgress ]
            ]
        ]
