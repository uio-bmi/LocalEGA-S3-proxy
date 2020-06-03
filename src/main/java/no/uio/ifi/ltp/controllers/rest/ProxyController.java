package no.uio.ifi.ltp.controllers.rest;

import lombok.extern.slf4j.Slf4j;
import no.uio.ifi.tc.TSDFileAPIClient;
import no.uio.ifi.tc.model.pojo.Chunk;
import no.uio.ifi.tc.model.pojo.ResumableUpload;
import no.uio.ifi.tc.model.pojo.Token;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;

/**
 * REST controller for proxying TSD File API requests.
 */
@Slf4j
@RestController
public class ProxyController {

    private static final String TOKEN_TYPE = "elixir";

    @Value("${tsd.app-id}")
    private String tsdAppId;

    @Autowired
    private TSDFileAPIClient tsdFileAPIClient;

    /**
     * Streams the file to the TSD File API.
     *
     * @param inputStream         Binary file stream.
     * @param bearerAuthorization Elixir AAI token.
     * @param fileName            File name.
     * @param uploadId            Upload ID.
     * @param chunk               Chunk number.
     * @param fileSize            File size.
     * @param md5                 MD5 digest.
     * @return Response code and test for the operation.
     * @throws IOException In case of I/O error.
     */
    @PatchMapping("/stream/{fileName}")
    public ResponseEntity<?> stream(InputStream inputStream,
                                    @RequestHeader(HttpHeaders.PROXY_AUTHORIZATION) String bearerAuthorization,
                                    @PathVariable("fileName") String fileName,
                                    @RequestParam(value = "uploadId", required = false) String uploadId,
                                    @RequestParam(value = "chunk", required = false) String chunk,
                                    @RequestParam(value = "fileSize", required = false) String fileSize,
                                    @RequestParam(value = "md5", required = false) String md5,
                                    @RequestParam(value = "sha256", required = false) String sha256) throws IOException {
        Token token = tsdFileAPIClient.getToken(TOKEN_TYPE, TOKEN_TYPE, getElixirAAIToken(bearerAuthorization));

        byte[] chunkBytes = inputStream.readAllBytes();

        // new upload
        if (StringUtils.isEmpty(uploadId)) {
            Chunk response = tsdFileAPIClient.initializeResumableUpload(token.getToken(), tsdAppId, chunkBytes, fileName);
            return validateChunkChecksum(token, response, md5);
        }

        // finalizing upload
        if ("end".equalsIgnoreCase(chunk)) {
            return ResponseEntity.ok(tsdFileAPIClient.finalizeResumableUpload(token.getToken(), tsdAppId, uploadId));
        }

        // uploading an intermediate chunk
        Chunk response = tsdFileAPIClient.uploadChunk(token.getToken(), tsdAppId, Long.parseLong(chunk), chunkBytes, uploadId);
        return validateChunkChecksum(token, response, md5);
    }

    private ResponseEntity<?> validateChunkChecksum(Token token, Chunk response, String md5) {
        ResumableUpload resumableUpload = tsdFileAPIClient.getResumableUpload(token.getToken(), tsdAppId, response.getId()).orElseThrow();
        if (!md5.equalsIgnoreCase(resumableUpload.getMd5Sum())) {
            tsdFileAPIClient.deleteResumableUpload(token.getToken(), tsdAppId, response.getId());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Checksum mismatch. Resumable upload interrrupted and can't be resumed. Please, re-upload the whole file.");
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Lists uploaded files.
     *
     * @param bearerAuthorization Elixir AAI token.
     * @return List of uploaded files.
     */
    @GetMapping("/files")
    public ResponseEntity<?> getFiles(@RequestHeader(HttpHeaders.PROXY_AUTHORIZATION) String bearerAuthorization) {
        Token token = tsdFileAPIClient.getToken(TOKEN_TYPE, TOKEN_TYPE, getElixirAAIToken(bearerAuthorization));
        return ResponseEntity.ok(tsdFileAPIClient.listFiles(token.getToken(), tsdAppId));
    }

    /**
     * Deletes uploaded file.
     *
     * @param bearerAuthorization Elixir AAI token.
     * @param fileName            File name.
     * @return Response code and text for the operation.
     */
    @DeleteMapping("/files")
    public ResponseEntity<?> deleteFile(@RequestHeader(HttpHeaders.PROXY_AUTHORIZATION) String bearerAuthorization,
                                        @RequestParam(value = "fileName") String fileName) {
        Token token = tsdFileAPIClient.getToken(TOKEN_TYPE, TOKEN_TYPE, getElixirAAIToken(bearerAuthorization));
        return ResponseEntity.ok(tsdFileAPIClient.deleteFile(token.getToken(), tsdAppId, fileName));
    }

    /**
     * Lists resumable uploads.
     *
     * @param bearerAuthorization Elixir AAI token.
     * @param uploadId            Upload ID.
     * @return List of resumable uploads.
     */
    @GetMapping("/resumables")
    public ResponseEntity<?> getResumables(@RequestHeader(HttpHeaders.PROXY_AUTHORIZATION) String bearerAuthorization,
                                           @RequestParam(value = "uploadId", required = false) String uploadId) {
        Token token = tsdFileAPIClient.getToken(TOKEN_TYPE, TOKEN_TYPE, getElixirAAIToken(bearerAuthorization));
        if (StringUtils.isEmpty(uploadId)) {
            return ResponseEntity.ok(tsdFileAPIClient.listResumableUploads(token.getToken(), tsdAppId));
        } else {
            return ResponseEntity.ok(tsdFileAPIClient.getResumableUpload(token.getToken(), tsdAppId, uploadId));
        }
    }

    /**
     * Deletes resumable upload.
     *
     * @param bearerAuthorization Elixir AAI token.
     * @param uploadId            Upload ID.
     * @return Response code and text for the operation.
     */
    @DeleteMapping("/resumables")
    public ResponseEntity<?> deleteResumable(@RequestHeader(HttpHeaders.PROXY_AUTHORIZATION) String bearerAuthorization,
                                             @RequestParam(value = "uploadId") String uploadId) {
        Token token = tsdFileAPIClient.getToken(TOKEN_TYPE, TOKEN_TYPE, getElixirAAIToken(bearerAuthorization));
        return ResponseEntity.ok(tsdFileAPIClient.deleteResumableUpload(token.getToken(), tsdAppId, uploadId));
    }

    protected String getElixirAAIToken(String bearerAuthorization) {
        return bearerAuthorization.replace("Bearer ", "");
    }

}
