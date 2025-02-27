package com.odysee.app.utils;

import android.os.Build;
import android.util.Log;

import org.bitcoinj.core.Base58;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.odysee.app.exceptions.ApiCallException;
import com.odysee.app.exceptions.LbryRequestException;
import com.odysee.app.exceptions.LbryResponseException;
import com.odysee.app.model.Claim;
import com.odysee.app.model.ClaimCacheKey;
import com.odysee.app.model.ClaimSearchCacheValue;
import com.odysee.app.model.LbryFile;
import com.odysee.app.model.OdyseeCollection;
import com.odysee.app.model.Tag;
import com.odysee.app.model.Transaction;
import com.odysee.app.model.WalletBalance;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class Lbry {
    private static final Object lock = new Object();
    public static final Map<ClaimCacheKey, Claim> claimCache = new HashMap<>();
    public static final Map<Map<String, Object>, ClaimSearchCacheValue> claimSearchCache = new HashMap<>();
    public static WalletBalance walletBalance = new WalletBalance();
    public static List<Tag> knownTags = new ArrayList<>();
    public static List<Tag> followedTags = new ArrayList<>();
    public static List<Claim> ownClaims = new ArrayList<>();
    public static List<Claim> ownChannels = new ArrayList<>(); // Make this a subset of ownClaims?
    public static List<String> abandonedClaimIds = new ArrayList<>();
    public static List<OdyseeCollection> ownCollections = new ArrayList<>();

    public static final int TTL_CLAIM_SEARCH_VALUE = 120000; // 2-minute TTL for cache
    public static final String API_CONNECTION_STRING = "https://api.na-backend.odysee.com/api/v1/proxy";
    public static final String TAG = "Lbry";

    // Values to obtain from LBRY SDK status
    public static boolean IS_STATUS_PARSED = false; // Check if the status has been parsed at least once
    public static final String PLATFORM = String.format("Android %s (API %d)", Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
    public static final String OS = "android";
    public static String INSTALLATION_ID = null;
    public static String NODE_ID = null;
    public static String DAEMON_VERSION = null;

    // JSON RPC API Call methods
    public static final String METHOD_RESOLVE = "resolve";
    public static final String METHOD_CLAIM_SEARCH = "claim_search";
    public static final String METHOD_FILE_LIST = "file_list";
    public static final String METHOD_FILE_DELETE = "file_delete";
    public static final String METHOD_GET = "get";
    public static final String METHOD_PUBLISH = "publish";

    public static final String METHOD_WALLET_BALANCE = "wallet_balance";
    public static final String METHOD_WALLET_ENCRYPT = "wallet_encrypt";
    public static final String METHOD_WALLET_DECRYPT = "wallet_decrypt";
    public static final String METHOD_VERSION = "version";

    public static final String METHOD_WALLET_LIST = "wallet_list";
    public static final String METHOD_WALLET_SEND = "wallet_send";
    public static final String METHOD_WALLET_STATUS = "wallet_status";
    public static final String METHOD_WALLET_UNLOCK = "wallet_unlock";
    public static final String METHOD_ADDRESS_IS_MINE = "address_is_mine";
    public static final String METHOD_ADDRESS_UNUSED = "address_unused";
    public static final String METHOD_ADDRESS_LIST = "address_list";
    public static final String METHOD_TRANSACTION_LIST = "transaction_list";
    public static final String METHOD_UTXO_RELEASE = "utxo_release";
    public static final String METHOD_SUPPORT_CREATE = "support_create";
    public static final String METHOD_SUPPORT_ABANDON = "support_abandon";
    public static final String METHOD_SYNC_HASH = "sync_hash";
    public static final String METHOD_SYNC_APPLY = "sync_apply";
    public static final String METHOD_PREFERENCE_GET = "preference_get";
    public static final String METHOD_PREFERENCE_SET = "preference_set";

    public static final String METHOD_COMMENT_CREATE = "comment_create";
    public static final String METHOD_COMMENT_REACT = "comment_react";

    public static final String METHOD_TXO_LIST = "txo_list";
    public static final String METHOD_TXO_SPEND = "txo_spend";

    public static final String METHOD_CHANNEL_ABANDON = "channel_abandon";
    public static final String METHOD_CHANNEL_CREATE = "channel_create";
    public static final String METHOD_CHANNEL_UPDATE = "channel_update";


    public static final String METHOD_CLAIM_LIST = "claim_list";
    public static final String METHOD_PURCHASE_LIST = "purchase_list";
    public static final String METHOD_STREAM_ABANDON = "stream_abandon";
    public static final String METHOD_STREAM_REPOST = "stream_repost";

    public static final String METHOD_COMMENT_LIST = "comment_list";

    public static KeyStore KEYSTORE;
    public static boolean SDK_READY = false;

    private Lbry() {
        // Ignore
    }

    public static void startupInit() {
        abandonedClaimIds = new ArrayList<>();
        ownChannels = new ArrayList<>();
        ownClaims = new ArrayList<>();
        knownTags = new ArrayList<>();
        followedTags = new ArrayList<>();
    }

    public static double getTotalBalance() {
        return walletBalance != null ? walletBalance.getTotal().doubleValue() : 0;
    }

    public static double getAvailableBalance() {
        return walletBalance != null ? Lbry.walletBalance.getAvailable().doubleValue() : 0;
    }

    public static void parseStatus(String response) {
        try {
            JSONObject json = parseSdkResponse(response);
            INSTALLATION_ID = json.getString("installation_id");
            if (json.has("lbry_id")) {
                // if DHT is not enabled, lbry_id won't be set
                NODE_ID = json.getString("lbry_id");
            }
            IS_STATUS_PARSED = true;
        } catch (JSONException | LbryResponseException ex) {
            // pass
            Log.e(TAG, "Could not parse status response.", ex);
        }
    }

    public static Response apiCall(String method, Map<String, Object> params, String connectionString) throws LbryRequestException {
        return apiCall(method, params, connectionString, null);
    }

    public static Response apiCall(String method, Map<String, Object> params) throws LbryRequestException {
        return apiCall(method, params, API_CONNECTION_STRING, null);
    }

    public static Response apiCall(String method, Map<String, Object> params, String connectionString, String authToken) throws LbryRequestException {
        long counter = new Double(System.currentTimeMillis() / 1000.0).longValue();
        if (Helper.isNullOrEmpty(authToken) &&
                params != null && params.containsKey("auth_token") && params.get("auth_token") != null) {
            authToken = params.get("auth_token").toString();
            params.remove("auth_token");
        }
        JSONObject requestParams = buildJsonParams(params);
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("jsonrpc", "2.0");
            requestBody.put("method", method);
            requestBody.put("params", requestParams);
            requestBody.put("counter", counter);
        } catch (JSONException ex) {
            throw new LbryRequestException("Could not build the JSON request body.", ex);
        }

        RequestBody body = RequestBody.create(requestBody.toString(), Helper.JSON_MEDIA_TYPE);
        Request.Builder requestBuilder = new Request.Builder().url(connectionString).post(body);

        if (!Helper.isNullOrEmpty(authToken)) {
            requestBuilder.addHeader("X-Lbry-Auth-Token", authToken);
        }

        Request request =  requestBuilder.build();
        OkHttpClient client = new OkHttpClient.Builder().
                writeTimeout(300, TimeUnit.SECONDS).
                readTimeout(300, TimeUnit.SECONDS).
                build();

        try {
            return client.newCall(request).execute();
        } catch (IOException ex) {
            throw new LbryRequestException(String.format("\"%s\" method to %s failed", method, connectionString), ex);
        }
    }

    public static JSONObject buildJsonParams(Map<String, Object> params) {
        JSONObject jsonParams = new JSONObject();
        if (params != null) {
            try {
                for (Map.Entry<String, Object> param : params.entrySet()) {
                    Object value = param.getValue();
                    if (value instanceof List) {
                        value = Helper.jsonArrayFromList((List) value);
                    }
                    if (value instanceof Double) {
                        jsonParams.put(param.getKey(), (double) value);
                    } else {
                        jsonParams.put(param.getKey(), value == null ? JSONObject.NULL : value);
                    }
                }
            } catch (JSONException ex) {
                // pass
            }
        }

        return jsonParams;
    }

    public static Object parseResponse(Response response) throws LbryResponseException {
        String responseString = null;
        try {
            responseString = response.body().string();
            JSONObject json = new JSONObject(responseString);
            if (response.code() >= 200 && response.code() < 300) {
                if (json.has("result")) {
                    if (json.isNull("result")) {
                        return null;
                    }
                    return json.get("result");
                } else {
                    processErrorJson(json);
                }
            }

            processErrorJson(json);
        } catch (JSONException | IOException ex) {
            throw new LbryResponseException(String.format("Could not parse response: %s", responseString), ex);
        }

        return null;
    }

    private static void processErrorJson(JSONObject json) throws JSONException, LbryResponseException {
        if (json.has("error")) {
            String errorMessage = null;
            Object jsonError = json.get("error");
            if (jsonError instanceof String) {
                errorMessage = jsonError.toString();
            } else {
                errorMessage = ((JSONObject) jsonError).getString("message");
            }
            throw new LbryResponseException(!Helper.isNullOrEmpty(errorMessage) ? errorMessage : json.getString("error"));
        } else {
            throw new LbryResponseException("Protocol error with unknown response signature.");
        }
    }

    public static JSONObject parseSdkResponse(String responseString) throws LbryResponseException {
        try {
            JSONObject json = new JSONObject(responseString);
            if (json.has("error")) {
                String errorMessage = null;
                Object jsonError = json.get("error");
                if (jsonError instanceof String) {
                    errorMessage = jsonError.toString();
                } else {
                    errorMessage = ((JSONObject) jsonError).getString("message");
                }
                throw new LbryResponseException(json.getString("error"));
            }

            return json;
        } catch (JSONException ex) {
            throw new LbryResponseException(String.format("Could not parse response: %s", responseString), ex);
        }
    }

    /**
     * API Calls
     */
    public static Claim resolve(String url, String connectionString) throws ApiCallException {
        List<Claim> results = resolve(Arrays.asList(url), connectionString);
        return results.size() > 0 ? results.get(0) : null;
    }
    public static List<Claim> resolve(List<String> urls, String connectionString) throws ApiCallException {
        List<Claim> claims = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        params.put("urls", urls);
        try {
            JSONObject result = (JSONObject) parseResponse(apiCall(METHOD_RESOLVE, params, connectionString));
            Iterator<String> keys = result.keys();
            if (keys != null) {
                while (keys.hasNext()) {
                    Claim claim = Claim.fromJSONObject(result.getJSONObject(keys.next()));
                    claims.add(claim);

                    addClaimToCache(claim);
                }
            }
        } catch (LbryRequestException | LbryResponseException | JSONException ex) {
            throw new ApiCallException("Could not execute resolve call", ex);
        }

        return claims;
    }
    public static List<Transaction> transactionList(int page, int pageSize, String authToken) throws ApiCallException {
        List<Transaction> transactions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (page > 0) {
            params.put("page", page);
        }
        if (pageSize > 0) {
            params.put("page_size", pageSize);
        }
        if (authToken != "") {
            params.put("auth_token", authToken);
        }
        try {
            JSONObject result = (JSONObject) parseResponse(apiCall(METHOD_TRANSACTION_LIST, params, API_CONNECTION_STRING));
            JSONArray items = result.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                Transaction tx = Transaction.fromJSONObject(items.getJSONObject(i));
                transactions.add(tx);
            }
        } catch (LbryRequestException | LbryResponseException | JSONException ex) {
            throw new ApiCallException("Could not execute transaction_list call", ex);
        }

        return transactions;
    }

    public static LbryFile get(boolean saveFile) throws ApiCallException {
        LbryFile file = null;
        Map<String, Object> params = new HashMap<>();
        params.put("save_file", saveFile);
        try {
            JSONObject result = (JSONObject) parseResponse(apiCall(METHOD_GET, params));
            file = LbryFile.fromJSONObject(result);

            if (file != null) {
                String fileClaimId = file.getClaimId();
                if (!Helper.isNullOrEmpty(fileClaimId)) {
                    ClaimCacheKey key = new ClaimCacheKey();
                    key.setClaimId(fileClaimId);
                    if (claimCache.containsKey(key)) {
                        claimCache.get(key).setFile(file);
                    }
                }
            }
        } catch (LbryRequestException | LbryResponseException ex) {
            throw new ApiCallException("Could not execute resolve call", ex);
        }

        return file;
    }

    public static List<LbryFile> fileList(String claimId, boolean downloads, int page, int pageSize) throws ApiCallException {
        List<LbryFile> files = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (!Helper.isNullOrEmpty(claimId)) {
            params.put("claim_id", claimId);
        }
        if (downloads) {
            params.put("download_path", null);
            params.put("comparison", "ne");
        }
        if (page > 0) {
            params.put("page", page);
        }
        if (pageSize > 0) {
            params.put("page_size", pageSize);
        }
        try {
            JSONObject result = (JSONObject) parseResponse(apiCall(METHOD_FILE_LIST, params));
            JSONArray items = result.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject fileObject = items.getJSONObject(i);
                LbryFile file = LbryFile.fromJSONObject(fileObject);
                files.add(file);

                String fileClaimId = file.getClaimId();
                if (!Helper.isNullOrEmpty(fileClaimId)) {
                    ClaimCacheKey key = new ClaimCacheKey();
                    key.setClaimId(fileClaimId);
                    if (claimCache.containsKey(key)) {
                        claimCache.get(key).setFile(file);
                    }
                }
            }
        } catch (LbryRequestException | LbryResponseException | JSONException ex) {
            throw new ApiCallException("Could not execute resolve call", ex);
        }

        return files;
    }

    private static final String[] listParamTypes = new String[] {
            "any_tags", "channel_ids", "order_by", "not_tags", "not_channel_ids", "urls"
    };

    // build claim search for surf mode
    public static Map<String, Object> buildClaimSearchOptions(
            String claimType, List<String> notTags, List<String> channelIds, List<String> orderBy, long maxDuration, int limitClaimsPerChannel, int page, int pageSize) {
        return buildClaimSearchOptions(
                Collections.singletonList(claimType),
                null,
                notTags,
                null,
                channelIds,
                null,
                orderBy,
                null,
                maxDuration,
                limitClaimsPerChannel,
                page,
                pageSize);
    }

    public static Map<String, Object> buildClaimSearchOptions(
            String claimType,
            List<String> anyTags,
            List<String> notTags,
            List<String> claimIds,
            List<String> channelIds,
            List<String> notChannelIds,
            List<String> orderBy,
            String releaseTime,
            int page,
            int pageSize) {
        return buildClaimSearchOptions(
                Collections.singletonList(claimType),
                anyTags,
                notTags,
                claimIds,
                channelIds,
                notChannelIds,
                orderBy,
                releaseTime,
                0,
                0,
                page,
                pageSize);
    }

    public static Map<String, Object> buildClaimSearchOptions(
            List<String> claimType,
            List<String> anyTags,
            List<String> notTags,
            List<String> claimIds,
            List<String> channelIds,
            List<String> notChannelIds,
            List<String> orderBy,
            String releaseTime,
            long maxDuration,
            int limitClaimsPerChannel,
            int page,
            int pageSize) {
        Map<String, Object> options = new HashMap<>();
        if (claimType != null && claimType.size() > 0) {
            options.put("claim_type", claimType);
        }
        options.put("no_totals", true);
        options.put("page", page);
        options.put("page_size", pageSize);
        if (!Helper.isNullOrEmpty(releaseTime)) {
            options.put("release_time", releaseTime);
        }
        if (maxDuration > 0) {
            options.put("duration", String.format("<%d", maxDuration));
        }
        if (limitClaimsPerChannel > 0) {
            options.put("limit_claims_per_channel", limitClaimsPerChannel);
        }

        addClaimSearchListOption("any_tags", anyTags, options);
        addClaimSearchListOption("not_tags", notTags, options);
        addClaimSearchListOption("claim_ids", claimIds, options);
        addClaimSearchListOption("channel_ids", channelIds, options);
        addClaimSearchListOption("not_channel_ids", notChannelIds, options);
        addClaimSearchListOption("order_by", orderBy, options);

        return options;
    }

    private static void addClaimSearchListOption(String key, List<String> list, Map<String, Object> options) {
        if (list != null && list.size() > 0) {
            options.put(key, list);
        }
    }

    public static List<Claim> claimSearch(Map<String, Object> options, String connectionString) throws ApiCallException {
        if (claimSearchCache.containsKey(options)) {
            ClaimSearchCacheValue value = claimSearchCache.get(options);
            if (value != null && !value.isExpired(TTL_CLAIM_SEARCH_VALUE)) {
                return claimSearchCache.get(options).getClaims();
            }
        }

        List<Claim> claims = new ArrayList<>();
        try {
            JSONObject result = (JSONObject) parseResponse(apiCall(METHOD_CLAIM_SEARCH, options, connectionString));
            JSONArray items;
            if (result != null) {
                items = result.getJSONArray("items");

                for (int i = 0; i < items.length(); i++) {
                    Claim claim = Claim.fromJSONObject(items.getJSONObject(i));
                    String claimValueType = claim.getValueType();
                    String claimMediaType = claim.getMediaType();

                    // Using Java Stream API to make it easier to add new future claim types
                    List<String> claimTypes = Stream.of(Claim.TYPE_COLLECTION, Claim.TYPE_REPOST, Claim.TYPE_CHANNEL)
                                                    .collect(Collectors.toList());

                    // For now, only claims which are audio, videos, playlists or already/scheduled livestreaming now can be viewed
                    if (claimTypes.contains(claimValueType.toLowerCase())
                            || !claim.hasSource()
                            || (claim.hasSource() && (claimMediaType.contains("video") || claimMediaType.contains("audio")))) {
                        claims.add(claim);
                    }

                    addClaimToCache(claim);
                }
            }

            claimSearchCache.put(options, new ClaimSearchCacheValue(claims, System.currentTimeMillis()));
        } catch (LbryRequestException | LbryResponseException | JSONException ex) {
            throw new ApiCallException("Could not execute resolve call", ex);
        }

        return claims;
    }

    public static Map<String, Object> buildSingleParam(String key, Object value) {
        Map<String, Object> params = new HashMap<>();
        params.put(key, value);
        return params;
    }

    /**
     * Call to return a generic JSONObject which can be further parsed as required
     * @param method
     * @param params
     * @return
     */
    public static Object genericApiCall(String method, Map<String, Object> params) throws ApiCallException {
        Object response = null;
        try {
            response = parseResponse(apiCall(method, params));
        } catch (LbryRequestException | LbryResponseException ex) {
            throw new ApiCallException(String.format("Could not execute %s call: %s", method, ex.getMessage()), ex);
        }
        return response;
    }
    public static Object authenticatedGenericApiCall(String method, Map<String, Object> params, String authToken) throws ApiCallException {
        Object response = null;
        try {
            response = parseResponse(apiCall(method, params, API_CONNECTION_STRING, authToken));
        } catch (LbryRequestException | LbryResponseException ex) {
            throw new ApiCallException(String.format("Could not execute %s call: %s", method, ex.getMessage()), ex);
        }
        return response;
    }
    public static Object directApiCall(String method, String authToken) throws ApiCallException {
        Map<String, Object> params = new HashMap<>();
        params.put("auth_token", authToken);
        Object response = null;
        try {
            response = parseResponse(apiCall(method, params, API_CONNECTION_STRING));
        } catch (LbryRequestException | LbryResponseException ex) {
            throw new ApiCallException(String.format("Could not execute %s call: %s", method, ex.getMessage()), ex);
        }
        return response;
    }

    /**
     * @deprecated Use authenticatedGenericApiCall(String, Map, String) instead
     * @param method
     * @param p
     * @param authToken
     * @return
     * @throws ApiCallException
     */
    @Deprecated
    public static Object directApiCall(String method, Map<String, Object> p, String authToken) throws ApiCallException {
        p.put("auth_token", authToken);
        Object response = null;
        try {
            response = parseResponse(apiCall(method, p, API_CONNECTION_STRING));
        } catch (LbryRequestException | LbryResponseException ex) {
            throw new ApiCallException(String.format("Could not execute %s call: %s", method, ex.getMessage()), ex);
        }
        return response;
    }
    public static Object genericApiCall(String method, String authToken) throws ApiCallException {
        return directApiCall(method, authToken);
    }
    public static Object genericApiCall(String method) throws ApiCallException {
        return genericApiCall(method, (String) null);
    }
    public static void addFollowedTag(Tag tag) {
        synchronized (lock) {
            if (!followedTags.contains(tag)) {
                followedTags.add(tag);
            }
        }
    }
    public static void removeFollowedTag(Tag tag) {
        synchronized (lock) {
            followedTags.remove(tag);
        }
    }
    public static void addKnownTag(Tag tag) {
        synchronized (lock) {
            if (!knownTags.contains(tag)) {
                knownTags.add(tag);
            }
        }
    }

    public static void addClaimToCache(Claim claim) {
        ClaimCacheKey fullKey = ClaimCacheKey.fromClaim(claim);
        ClaimCacheKey shortUrlKey = ClaimCacheKey.fromClaimShortUrl(claim);
        ClaimCacheKey permanentUrlKey = ClaimCacheKey.fromClaimPermanentUrl(claim);

        if (!Helper.isNullOrEmpty(fullKey.getUrl())) {
            claimCache.put(fullKey, claim);
        }
        if (!Helper.isNullOrEmpty(permanentUrlKey.getUrl())) {
            claimCache.put(permanentUrlKey, claim);
        }
        if (!Helper.isNullOrEmpty(shortUrlKey.getUrl())) {
            claimCache.put(shortUrlKey, claim);
        }
    }

    public static void unsetFilesForCachedClaims(List<String> claimIds) {
        for (String claimId : claimIds) {
            ClaimCacheKey key = new ClaimCacheKey();
            key.setClaimId(claimId);
            if (claimCache.containsKey(key)) {
                claimCache.get(key).setFile(null);
            }
        }
    }

    public static List<OdyseeCollection> loadOwnCollections(String authToken) throws  ApiCallException, JSONException  {
        Map<String, Object> options = new HashMap<>();
        options.put("claim_type", Collections.singletonList("collection"));
        options.put("page", 1);
        options.put("page_size", 999);
        options.put("resolve", true);

        JSONObject result = (JSONObject) Lbry.authenticatedGenericApiCall(Lbry.METHOD_CLAIM_LIST, options, authToken);
        JSONArray items = result.getJSONArray("items");
        if (items.length() == ownCollections.size()) {
            // do a very shallow comparison, so that we  don't have to do a long load process every time
            return ownCollections;
        }

        List<OdyseeCollection> collections = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            Claim claim = Claim.fromJSONObject(items.getJSONObject(i));

            //  for each claim, we need to claim_search the "claims" in values
            //  not very efficient, would've been nicer to have claim urls instead of just IDs
            if (!Helper.isNullOrEmpty(claim.getClaimId())) {
                Map<String, Object> opt = Lbry.buildClaimSearchOptions(
                        null,
                        null,
                        null,
                        new ArrayList<>(claim.getClaimIds()),
                        null,
                        null,
                        null,
                        null,
                        0,
                        0,
                        1,
                        999
                );
                List<Claim> claimsInCollection = Lbry.claimSearch(opt, API_CONNECTION_STRING);
                List<String> claimUrls = new ArrayList<>();
                for (Claim collectionClaim : claimsInCollection) {
                    claimUrls.add(collectionClaim.getPermanentUrl());
                }

                OdyseeCollection actualCollection = OdyseeCollection.fromClaim(claim,  claimUrls);
                collections.add(actualCollection);
            }
        }

        ownCollections = new ArrayList<>(collections);

        return collections;
    }

    public static boolean isOwnedCollection(String id) {
        for (OdyseeCollection collection : ownCollections) {
            if (id.equalsIgnoreCase(collection.getId())) {
                return true;
            }
        }
        return false;
    }
    public static OdyseeCollection getOwnCollectionById(String id) {
        for (OdyseeCollection collection : ownCollections) {
            if (id.equalsIgnoreCase(collection.getId())) {
                return collection;
            }
        }
        return null;
    }

    public static String generateId() {
        return generateId(64);
    }
    public static String generateId(int numBytes) {
        byte[] arr = new byte[numBytes];
        new Random().nextBytes(arr);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-384");
            byte[] hash = md.digest(arr);
            return Base58.encode(hash);
        } catch (NoSuchAlgorithmException e) {
            // pass
            return null;
        }
    }
}
