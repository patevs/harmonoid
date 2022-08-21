/// This file is a part of Harmonoid (https://github.com/harmonoid/harmonoid).
///
/// Copyright © 2020-2022, Hitesh Kumar Saini <saini123hitesh@gmail.com>.
/// All rights reserved.
///
/// Use of this source code is governed by the End-User License Agreement for Harmonoid that can be found in the EULA.txt file.
///

package com.alexmercerind.harmonoid;

import java.util.HashMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import java9.util.concurrent.CompletableFuture;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import io.flutter.Log;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import android.media.MediaMetadataRetriever;

class MetadataRetrieverImplementation extends MediaMetadataRetriever {
    private final String uri;

    public MetadataRetrieverImplementation(String uri) {
        super();
        this.uri = uri;
    }

    public HashMap<String, Object> getMetadata() {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("uri", uri);
        metadata.put("trackName", extractMetadata(METADATA_KEY_TITLE));
        metadata.put("trackArtistNames", extractMetadata(METADATA_KEY_ARTIST));
        metadata.put("albumName", extractMetadata(METADATA_KEY_ALBUM));
        metadata.put("albumArtistName", extractMetadata(METADATA_KEY_ALBUMARTIST));
        // Read [trackNumber] & [albumLength] from the passed source.
        final String trackNumber = extractMetadata(METADATA_KEY_CD_TRACK_NUMBER);
        final String albumLength = extractMetadata(METADATA_KEY_NUM_TRACKS);
        try {
            // If [albumLength] is non-null, insert into the final [metadata] [HashMap].
            if (albumLength != null) {
                metadata.put("albumLength", albumLength);
            }
            // Operate over [trackNumber] key, if it is non-null.
            if (trackNumber != null) {
                final String[] trackNumberData = trackNumber.split("/");
                // Some MP3s store [trackNumber] as `4/20`. Split across `/` & store first section
                // as [trackNumber] inside the final metadata [HashMap].
                metadata.put("trackNumber", trackNumberData[0].trim());
                // If [albumLength] was not already put inside [metadata] from
                // [METADATA_KEY_NUM_TRACKS] and we found more than one element(s) in
                // [trackNumberSplit], then use the last section from the [trackNumber] as
                // [albumLength].
                if (!metadata.containsKey("albumLength")) {
                    if (trackNumberData.length > 1) {
                        metadata.put(
                                "albumLength",
                                trackNumberData[trackNumberData.length - 1].trim()
                        );
                    } else {
                        metadata.put("albumLength", null);
                    }
                }
            }
        } catch (Exception exception) {
            metadata.put("trackNumber", null);
            metadata.put("albumLength", null);
        }
        final String year = extractMetadata(METADATA_KEY_YEAR);
        final String date = extractMetadata(METADATA_KEY_DATE);
        try {
            metadata.put("year", year.trim());
        } catch (Exception exception) {
            try {
                // If year is not found (will throw [NullPointerException]), then use the date tag
                // to extract year.
                // Generally, year tag seems to stored as 2002.07.23, 2002/07/23 or 2002-07-23.
                metadata.put("year", date.split("[.\\-/]")[0].trim());
            } catch (Exception e) {
                metadata.put("year", null);
            }
        }
        metadata.put("genre", extractMetadata(METADATA_KEY_GENRE));
        metadata.put("authorName", extractMetadata(METADATA_KEY_AUTHOR));
        metadata.put("writerName", extractMetadata(METADATA_KEY_WRITER));
        metadata.put("discNumber", extractMetadata(METADATA_KEY_DISC_NUMBER));
        metadata.put("mimeType", extractMetadata(METADATA_KEY_MIMETYPE));
        metadata.put("duration", extractMetadata(METADATA_KEY_DURATION));
        metadata.put("bitrate", extractMetadata(METADATA_KEY_BITRATE));
        Log.d("Harmonoid", metadata.toString());
        return metadata;
    }
}

public class MetadataRetriever implements MethodCallHandler {
    @SuppressWarnings("UnusedDeclaration")
    @Override
    public void onMethodCall(
            @NonNull final MethodCall call,
            @NonNull final Result result
    ) {
        // Passed "uri" inside the arguments must be a String interpretation of a URI, which follows
        // a scheme such as `file://` or `http://` etc. Where as, "coverDirectory" must be a direct
        // path to the file system directory where the cover art will be extracted (not a URI).
        if (call.method.equals("MetadataRetriever")) {
            final String[] uri = {call.argument("uri")};
            final String[] coverDirectory = {call.argument("coverDirectory")};
            final Boolean[] waitUntilAlbumArtIsSaved = {call.argument("waitUntilAlbumArtIsSaved")};
            if (waitUntilAlbumArtIsSaved[0] == null) {
                waitUntilAlbumArtIsSaved[0] = false;
            }
            if (uri[0] != null) {
                Log.d("Harmonoid", uri[0]);
            }
            // Run [MediaMetadataRetriever] on another thread. Extracting metadata of a [File] is
            // a heavy operation & causes substantial jitter in the UI.
            CompletableFuture.runAsync(() -> {
                final MetadataRetrieverImplementation retriever = new MetadataRetrieverImplementation(uri[0]);
                // Try to get the [FileInputStream] from the passed [uri].
                try {
                    // Only used for `file://` scheme.
                    FileInputStream input = null;
                    HashMap<String, Object> metadata;
                    // Handle `file://`.
                    if (uri[0].toLowerCase().startsWith("file://")) {
                        input = new FileInputStream(Uri.parse(uri[0]).getPath());
                        // Set data source using [FileDescriptor], which tends to be safer.
                        retriever.setDataSource(input.getFD());
                        metadata = retriever.getMetadata();
                        metadata.put("uri", uri[0]);
                        // Return the metadata.
                        final HashMap<String, Object> response = metadata;
                        if (!waitUntilAlbumArtIsSaved[0]) {
                            new Handler(
                                    Looper.getMainLooper()).post(() -> result.success(response)
                            );
                        }
                    } else {
                        // Handle other URI schemes. Expected to be network URLs. Hope for the best!
                        retriever.setDataSource(uri[0], new HashMap<>());
                        metadata = retriever.getMetadata();
                        metadata.put("uri", uri[0]);
                        final HashMap<String, Object> response = metadata;
                        if (!waitUntilAlbumArtIsSaved[0]) {
                            new Handler(
                                    Looper.getMainLooper()).post(() -> result.success(response)
                            );
                        }
                    }
                    // Now proceed to save the album art in background.
                    String trackName = (String) metadata.get("trackName");
                    // See [Track] model within Harmonoid.
                    if (trackName == null) {
                        // If [trackName] is found null, we extract a human-understandable [String]
                        // from the [File]'s original name.
                        if (uri[0].endsWith("/")) {
                            // Remove trailing `/`.
                            uri[0] = uri[0].substring(0, uri[0].length() - 1);
                        }
                        // Split across last `/` in the URI & keep it as [trackName].
                        trackName = uri[0].split("/")[uri[0].split("/").length - 1];
                        // We need to decode the URI component to get actual [File]'s name.
                        // Only done for `file://` scheme URIs. See Harmonoid's Track fromJson
                        // factory constructor for more information.
                        if (uri[0].toLowerCase().startsWith("file://")) {
                            try {
                                trackName = URLDecoder.decode(
                                        trackName,
                                        StandardCharsets.UTF_8.toString()
                                );
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    // See [Track] model within Harmonoid.
                    String albumName = (String) metadata.get("albumName");
                    if (albumName == null) {
                        albumName = "Unknown Album";
                    }
                    String albumArtistName = (String) metadata.get("albumArtistName");
                    if (albumArtistName == null) {
                        albumArtistName = "Unknown Artist";
                    }
                    // Remove trailing `/` from [coverDirectory] path.
                    if (coverDirectory[0].endsWith("/")) {
                        coverDirectory[0] = coverDirectory[0].substring(
                                0,
                                coverDirectory[0].length() - 1
                        );
                    }
                    // Final album art [File].
                    final File file = new File(
                            String.format(
                                    "%s/%s.PNG",
                                    coverDirectory[0],
                                    // See [albumArtFileName] getter on [Track] inside Harmonoid and
                                    // [kAlbumArtFileNameRegex].
                                    (trackName + albumName + albumArtistName).replaceAll(
                                            "[\\\\/:*?\"<>| ]"
                                            , ""
                                    )
                            )
                    );
                    Log.d("Harmonoid", file.getAbsolutePath());
                    if (!file.exists()) {
                        try {
                            byte[] embeddedPicture = retriever.getEmbeddedPicture();
                            if (embeddedPicture != null) {
                                // Recursively create directories to the specified album art [File] &
                                // also create the [File] if not already present at the location.
                                final boolean mkdirs = new File(coverDirectory[0]).mkdirs();
                                if (!file.exists()) {
                                    final boolean created = file.createNewFile();
                                }
                                final FileOutputStream output = new FileOutputStream(file);
                                output.write(retriever.getEmbeddedPicture());
                                output.close();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    try {
                        retriever.release();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    try {
                        if (input != null) {
                            input.close();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (waitUntilAlbumArtIsSaved[0]) {
                        new Handler(
                                Looper.getMainLooper()).post(() -> result.success(metadata)
                        );
                    }
                }
                // Return fallback [metadata] [HashMap], with only [uri] key present inside it.
                // In case a [FileNotFoundException] or [IOException] was thrown.
                catch (IOException exception) {
                    exception.printStackTrace();
                    final HashMap<String, Object> metadata = new HashMap<>();
                    metadata.put("uri", uri[0]);
                    new Handler(
                            Looper.getMainLooper()).post(() -> result.success(metadata)
                    );
                }
            });
        } else {
            result.notImplemented();
        }
    }
}