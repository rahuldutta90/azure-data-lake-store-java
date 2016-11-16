/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 * See License.txt in the project root for license information.
 */

package com.microsoft.azure.datalake.store;


import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.microsoft.azure.datalake.store.retrypolicies.NoRetryPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * The core class that does the actual network communication. All the REST methods
 * use this class to make HTTP calls.
 * <P>
 *     There are two calls in this class:
 *     makeSingleCall - this makes an HTTP request.
 *     makeCall - wraps retries around makeSingleCall
 * </P>
 */
class HttpTransport {

    private static final String API_VERSION = "2016-11-01"; // API version used in REST requests
    private static final Logger log = LoggerFactory.getLogger("com.microsoft.azure.datalake.store.HttpTransport");
    private static final Logger tokenlog = LoggerFactory.getLogger("com.microsoft.azure.datalake.store.HttpTransport.tokens");

    /**
     * calls {@link #makeSingleCall(ADLStoreClient, Operation, String, QueryParams, byte[], int, int, RequestOptions, OperationResponse) makeSingleCall}
     * in a retry loop. The retry policies are dictated by the {@link com.microsoft.azure.datalake.store.retrypolicies.RetryPolicy RetryPolicy} passed in.
     *
     * @param client the the {@link ADLStoreClient}
     * @param op the WebHDFS operation tp perform
     * @param path the path to operate on
     * @param queryParams query parameter names and values to include on the URL of the request
     * @param requestBody the body of the request, if applicable. can be {@code null}
     * @param offsetWithinContentsArray offset within the byte array passed in {@code requestBody}. Bytes starting
     *                                  at this offset will be sent to server
     * @param length number of bytes from {@code requestBody} to be sent
     * @param opts options to change the behavior of the call
     * @param resp response from the call, and any error info generated by the call
     */
    static void makeCall (ADLStoreClient client,
                                       Operation op,
                                       String path,
                                       QueryParams queryParams,
                                       byte[] requestBody,
                                       int offsetWithinContentsArray,
                                       int length,
                                       RequestOptions opts,
                                       OperationResponse resp
                                       )
    {
        if (opts == null) throw new IllegalArgumentException("RequestOptions parameter missing from call");
        if (resp == null) throw new IllegalArgumentException("OperationResponse parameter missing from call");


        if (opts.retryPolicy == null) {
            opts.retryPolicy = new NoRetryPolicy();
        }

        String clientRequestId;
        if (opts.requestid == null) {
            clientRequestId = UUID.randomUUID().toString();
        } else {
            clientRequestId = opts.requestid;
        }

        if (queryParams == null) queryParams = new QueryParams();
        queryParams.setOp(op);
        queryParams.setApiVersion(API_VERSION);

        int retryCount = 0;
        do {
            opts.requestid = clientRequestId + "." + Integer.toString(retryCount);
            resp.reset();
            long start = System.nanoTime();
            makeSingleCall(client, op, path, queryParams, requestBody, offsetWithinContentsArray, length, opts, resp);
            resp.lastCallLatency = System.nanoTime() - start;
            resp.lastCallLatency = resp.lastCallLatency / 1000000;   // convert from nanoseconds to milliseconds
            resp.numRetries = retryCount;
            String respLength = (resp.responseChunked ? "chunked" : Long.toString(resp.responseContentLength));
            String error = "";
            String outcome = null;
            if (isSuccessfulResponse(resp, op)) {
                resp.successful = true;
                outcome = "Succeeded";
                LatencyTracker.addLatency(opts.requestid, retryCount, resp.lastCallLatency, op.name,
                        length + resp.responseContentLength, client.getClientId());
            } else {
                resp.successful = false;
                outcome = "Failed";
                if (resp.ex!=null) {
                    error = resp.ex.getClass().getName();
                } else {
                    error = "HTTP" + resp.httpResponseCode + "(" + resp.remoteExceptionName + ")";
                }
                LatencyTracker.addError(opts.requestid, retryCount, resp.lastCallLatency, error, op.name,
                        length, client.getClientId());
                retryCount++;
                resp.exceptionHistory = resp.exceptionHistory == null ? error : resp.exceptionHistory + "," + error;
            }
            if (log.isDebugEnabled()) {
                String logline =
                        "HTTPRequest," + outcome +
                        ",cReqId:" + opts.requestid +
                        ",lat:" + Long.toString(resp.lastCallLatency) +
                        ",err:" + error +
                        ",Reqlen:" + length +
                        ",Resplen:" + respLength +
                        ",token_ns:" + Long.toString(resp.tokenAcquisitionLatency) +
                        ",sReqId:" + resp.requestId +
                        ",path:" + path +
                        ",qp:" + queryParams.serialize();
                log.debug(logline);
            }
        } while (!resp.successful && opts.retryPolicy.shouldRetry(resp.httpResponseCode, resp.ex));
    }

    private static boolean isSuccessfulResponse(OperationResponse resp, Operation op) {
        if (resp.ex != null) return false;
        if (!resp.successful) return false;
        if (resp.httpResponseCode >=100 && resp.httpResponseCode < 300) return true; // 1xx and 2xx return codes
        return false;         //anything else
    }

    /**
     * Does the actual HTTP call to server. All REST API calls use this method to make their HTTP calls.
     * <p>
     * This is a static, stateless, thread-safe method.
     * </P>
     *
     * @param client                    the the {@link ADLStoreClient}
     * @param op                        the WebHDFS operation tp perform
     * @param path                      the path to operate on
     * @param queryParams               query parameter names and values to include on the URL of the request
     * @param requestBody               the body of the request, if applicable. can be {@code null}
     * @param offsetWithinContentsArray offset within the byte array passed in {@code requestBody}. Bytes starting
     *                                  at this offset will be sent to server
     * @param length                    number of bytes from {@code requestBody} to be sent
     * @param opts                      options to change the behavior of the call
     * @param resp                      response from the call, and any error info generated by the call
     */
    private static void makeSingleCall(ADLStoreClient client,
                                       Operation op,
                                       String path,
                                       QueryParams queryParams,
                                       byte[] requestBody,
                                       int offsetWithinContentsArray,
                                       int length,
                                       RequestOptions opts,
                                       OperationResponse resp) {


        if (client == null) throw new IllegalArgumentException("client is null");
        if (client.getAccountName() == null || client.getAccountName().equals("")) {
            resp.successful = false;
            resp.message = "Account name or client is null or blank";
            return;
        }

        String token = null;
        long tokenStartTime = System.nanoTime();
        try {
            token = client.getAccessToken();
            if (token == null || token.equals("")) {
                resp.successful = false;
                resp.message = "Access token is null or blank";
                resp.tokenAcquisitionLatency = System.nanoTime() - tokenStartTime;
                return;
            }
        } catch (IOException ex) {
            resp.successful = false;
            resp.message = "Error fetching access token";
            resp.tokenAcquisitionLatency = System.nanoTime() - tokenStartTime;
            return;
        }
        resp.tokenAcquisitionLatency = System.nanoTime() - tokenStartTime;

        if (op == null) {
            resp.successful = false;
            resp.message = "operation is null";
            return;
        }

        if (path == null || path.trim().equals("")) {
            resp.successful = false;
            resp.message = "path is null";
            return;
        }

        resp.opCode = op.name;

        if (requestBody != null) {
            if (offsetWithinContentsArray < 0 ||
                    length < 0 ||
                    offsetWithinContentsArray + length < 0 || // integer overflow
                    offsetWithinContentsArray >= requestBody.length ||
                    offsetWithinContentsArray + length > requestBody.length) {
                throw new IndexOutOfBoundsException("offset+length overflows byte buffer for path " + path);
            }
        } else {
            if (offsetWithinContentsArray != 0 || length != 0) {
                throw new IndexOutOfBoundsException("Non-zero offset or length with null body for path " + path);
            }
        }


        // Build URL
        StringBuilder urlString = new StringBuilder();
        urlString.append(client.getHttpPrefix());    // http or https
        urlString.append("://");
        urlString.append(client.getAccountName());
        if (op.isExt) {
            urlString.append("/WebHdfsExt");
        } else {
            urlString.append("/webhdfs/v1");
        }

        String prefix = client.getFilePathPrefix();
        if (prefix != null) urlString.append(prefix);

        if (path.charAt(0) != '/') urlString.append('/');
        try {
            urlString.append((new URI(null, null, path, null)).toASCIIString());   // use URI to encode path
        } catch (URISyntaxException ex) {
            resp.successful = false;
            resp.message = "Invalid path " + path;
            return;
        }
        urlString.append('?');
        urlString.append(queryParams.serialize());

        URL url;
        try {
            url = new URL(urlString.toString());
        } catch (MalformedURLException ex) {
            resp.ex = ex;
            resp.successful = false;
            return;
        }

        HttpURLConnection conn = null;
        try {
            // Setup Http Request (method and headers)
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", token);
            conn.setRequestProperty("User-Agent", client.getUserAgent());
            conn.setRequestProperty("x-ms-client-request-id", opts.requestid);
            String latencyHeader = LatencyTracker.get();
            if (latencyHeader != null) conn.setRequestProperty("x-ms-adl-client-latency", latencyHeader);
            if (client.getTiHeaderValue() != null)
                conn.setRequestProperty("x-ms-tracking-info", client.getTiHeaderValue());
            conn.setConnectTimeout(opts.timeout);
            conn.setReadTimeout(opts.timeout);
            conn.setUseCaches(false);
            conn.setRequestMethod(op.method);
            conn.setDoInput(true);

            // populate request body if applicable
            if (!op.method.equals("GET")) {
                conn.setDoOutput(true);
                conn.setRequestMethod(op.method);
                OutputStream outStr = conn.getOutputStream();
                if (op.requiresBody && requestBody != null) {
                    outStr.write(requestBody, offsetWithinContentsArray, length);
                    outStr.close();
                } else {
                    // server *requires* a Content-Length header, and doesnt take absence of header as 0 (bad behavior)
                    // The only way to force java to send "Content-Length:0" is to do this.
                    // Setting Content-Length header to 0 using setRequestProprty doesnt work (bad behavior)
                    byte[] buf = new byte[]{};  // zero-length byte-array
                    outStr.write(buf);
                    outStr.close();
                }
            }

            // get Response Stream if applicable
            resp.httpResponseCode = conn.getResponseCode();
            resp.httpResponseMessage = conn.getResponseMessage();
            resp.requestId = conn.getHeaderField("x-ms-request-id");
            resp.responseContentLength = conn.getHeaderFieldLong("Content-Length", 0);
            String chunked = conn.getHeaderField("Transfer-Encoding");
            if (chunked != null && chunked.equals("chunked")) resp.responseChunked = true;

            // if request failed, then the body of an HTTP 4xx or 5xx response contains error info as JSon
            if (resp.httpResponseCode >= 400) {
                if (resp.httpResponseCode == 401 && tokenlog.isDebugEnabled()) {  // log auth token errors separately
                    String logline = "HTTPRequest,HTTP401,cReqId:" +
                            opts.requestid + ",sReqId:" +
                            resp.requestId + ",path:" +
                            path + ",token:" +
                            token;             // ok to log, since token doesn't seem to be working anyway
                    tokenlog.debug(logline);
                }
                if (resp.responseContentLength > 0 && conn.getErrorStream() != null) {
                    getCodesFromJSon(conn.getErrorStream(), resp);
                    return;
                }
            } else {
                consumeInputStream(conn.getErrorStream());  // read(ignore) and close if the stream exists
                if (op.returnsBody) {  // response stream will be handled by caller
                    resp.responseStream = conn.getInputStream();
                } else {    // read and discard response stream so it is consumed and connection can be reused
                    consumeInputStream(conn.getInputStream());
                }
            }
        } catch (IOException ex) {
            resp.ex = ex;
            resp.successful = false;
            if (conn != null) {
                try {
                    consumeInputStream(conn.getInputStream());  // read(ignore) and close if the stream exists
                    consumeInputStream(conn.getErrorStream());  // read(ignore) and close if the stream exists
                } catch (IOException ex2) {
                    // ignore, since we already have the root IOException - this part is just when cleaning up error stream
                }
            }
        }
    }

    static void consumeInputStream(InputStream istr) throws IOException {
        if (istr != null) {
            try {
                byte[] b = new byte[65536];
                while (istr.read(b) >= 0) ;        // read and discard
            } finally {
                istr.close();
            }
        }
    }

    private static void getCodesFromJSon(InputStream s, OperationResponse resp) {
        try {
            JsonFactory jf = new JsonFactory();
            JsonParser jp = jf.createParser(s);
            String fieldName, fieldValue;

            jp.nextToken();  // START_OBJECT - {
            jp.nextToken();  // FIELD_NAME - "RemoteException":
            jp.nextToken();  // START_OBJECT - {
            jp.nextToken();
            while (jp.hasCurrentToken()) {
                if (jp.getCurrentToken() == JsonToken.FIELD_NAME) {
                    fieldName = jp.getCurrentName();
                    jp.nextToken();
                    fieldValue = jp.getText();

                    if (fieldName.equals("exception")) resp.remoteExceptionName = fieldValue;
                    if (fieldName.equals("message")) resp.remoteExceptionMessage = fieldValue;
                    if (fieldName.equals("javaClassName")) resp.remoteExceptionJavaClassName = fieldValue;
                }
                jp.nextToken();
            }
            jp.close();
        } catch (IOException ex) {}
        finally {
            try {
                s.close();
            } catch (IOException ex) { }
        }
    }



}
