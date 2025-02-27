package com.odysee.app.model;

import androidx.annotation.Nullable;
import android.annotation.SuppressLint;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.odysee.app.utils.Helper;
import com.odysee.app.utils.LbryUri;
import com.odysee.app.utils.Predefined;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Claim {
    public static final String CLAIM_TYPE_CLAIM = "claim";
    public static final String CLAIM_TYPE_UPDATE = "update";
    public static final String CLAIM_TYPE_SUPPORT = "support";

    public static final String TYPE_STREAM = "stream";
    public static final String TYPE_CHANNEL = "channel";
    public static final String TYPE_REPOST = "repost";
    public static final String TYPE_COLLECTION = "collection";

    public static final String STREAM_TYPE_AUDIO = "audio";
    public static final String STREAM_TYPE_IMAGE = "image";
    public static final String STREAM_TYPE_VIDEO = "video";
    public static final String STREAM_TYPE_SOFTWARE = "software";

    public static final String ORDER_BY_EFFECTIVE_AMOUNT = "effective_amount";
    public static final String ORDER_BY_RELEASE_TIME = "release_time";
    public static final String ORDER_BY_TRENDING_GROUP = "trending_group";
    public static final String ORDER_BY_TRENDING_MIXED = "trending_mixed";

    public static final List<String> CLAIM_TYPES = Arrays.asList(TYPE_CHANNEL, TYPE_STREAM);
    public static final List<String> STREAM_TYPES = Arrays.asList(
            STREAM_TYPE_AUDIO, STREAM_TYPE_IMAGE, STREAM_TYPE_SOFTWARE, STREAM_TYPE_VIDEO
    );

    public static final String RELEASE_TIME_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    @EqualsAndHashCode.Include
    private boolean placeholder;
    private boolean placeholderAnonymous;
    private boolean loadingPlaceholder;
    private boolean featured;
    private boolean unresolved; // used for featured
    private String address;
    private String amount;
    private String canonicalUrl;
    @EqualsAndHashCode.Include
    private String claimId;
    private int claimSequence;
    private String claimOp;
    private long confirmations;
    private boolean decodedClaim;
    private long timestamp;
    private long height;
    private boolean isMine;
    private String name;
    private String normalizedName;
    private int nout;
    private String permanentUrl;
    private String shortUrl;
    private String txid;
    private String type; // claim | update | support
    private String valueType; // stream | channel | repost
    private Claim repostedClaim;
    private Claim signingChannel;
    private String repostChannelUrl;
    private boolean isChannelSignatureValid;
    private GenericMetadata value;
    private LbryFile file; // associated file if it exists
    private Meta meta;

    private boolean liked;
    private boolean disliked;
    // device it was viewed on (for view history)
    private String device;

    private boolean isLive;
    private String livestreamUrl;

    private boolean highlightLive;
    private int livestreamViewers;

    // Collections
    private List<String> claimIds;

    public static Claim claimFromOutput(JSONObject item) {
        // we only need name, permanent_url, txid and nout
        Claim claim = new Claim();
        claim.setClaimId(Helper.getJSONString("claim_id", null, item));
        claim.setName(Helper.getJSONString("name", null, item));
        claim.setPermanentUrl(Helper.getJSONString("permanent_url", null, item));
        claim.setTxid(Helper.getJSONString("txid", null, item));
        claim.setNout(Helper.getJSONInt("nout", -1, item));

        return claim;
    }

    public String getOutpoint() {
        return String.format("%s:%d", txid, nout);
    }

    public boolean isFree() {
        if (!(value instanceof StreamMetadata)) {
            return true;
        }

        Fee fee = ((StreamMetadata) value).getFee();
        return fee == null || Helper.parseDouble(fee.getAmount(), 0) == 0;
    }

    /**
     * Calculates price of claim in LBC once it is converted from USD
     * @param usdRate LBC/USD rate
     * @return
     */
    public BigDecimal getActualCost(double usdRate) {
        if (!(value instanceof StreamMetadata)) {
            return new BigDecimal(0);
        }

        Fee fee = ((StreamMetadata) value).getFee();
        try {
            if (fee != null) {
                double amount = Helper.parseDouble(fee.getAmount(), 0);
                if ("usd".equalsIgnoreCase(fee.getCurrency())) {
                    return new BigDecimal(String.valueOf(amount / usdRate));
                }

                return new BigDecimal(String.valueOf(amount)); // deweys
            }
        } catch (NumberFormatException ex) {
            // pass
        }

        return new BigDecimal(0);
    }

    public String getMediaType() {
        if (value instanceof StreamMetadata) {
            StreamMetadata metadata = (StreamMetadata) value;
            String mediaType = metadata.getSource() != null ? metadata.getSource().getMediaType() : null;
            return mediaType;
        }
        return null;
    }

    public boolean hasSource() {
        if (value instanceof StreamMetadata) {
            StreamMetadata metadata = (StreamMetadata) value;
            return metadata.getSource() != null;
        }
        return false;
    }

    public boolean isPlayable() {
        if (value instanceof StreamMetadata) {
            StreamMetadata metadata = (StreamMetadata) value;
            String mediaType = metadata.getSource() != null ? metadata.getSource().getMediaType() : null;
            if (mediaType != null) {
                return mediaType.startsWith("video") || mediaType.startsWith("audio");
            } else {
                return livestreamUrl != null;
            }
        }
        return false;
    }
    public boolean isViewable() {
        if (value instanceof StreamMetadata) {
            StreamMetadata metadata = (StreamMetadata) value;
            String mediaType = metadata.getSource() != null ? metadata.getSource().getMediaType() : null;
            if (mediaType != null) {
                return mediaType.startsWith("image") || mediaType.startsWith("text");
            }
        }
        return false;
    }
    public boolean isMature() {
        List<String> tags = getTags();
        if (tags != null && tags.size() > 0) {
            for (String tag : tags) {
                if (Predefined.MATURE_TAGS.contains(tag.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns highlightLive field value
     * @return Current highlightLive field value
     */
    public boolean isHighlightLive() {
        return highlightLive;
    }

    /**
     * Sets the highlightLive field value for this Claim object
     * @param highlightLive - true if you want claim list item to show "LIVE"/"SOON" instead of duration
     */
    public void setHighlightLive(boolean highlightLive) {
        this.highlightLive = highlightLive;
    }

    public String getThumbnailUrl() {
        if (value != null && value.getThumbnail() != null) {
            return value.getThumbnail().getUrl();
        }
        return null;
    }

    /**
     * Gets the URL from the CDN where getting the image file
     * @param width Pass zero for width and height for the full size image file
     * @param height Pass zero for width and height for the full size image file
     * @param q Desired quality for the image to be retrieved
     * @return URL from the CDN from where image can be retrieved
     */
    public String getThumbnailUrl(int width, int height, int q) {
        if (value != null && value.getThumbnail() != null && !Helper.isNullOrEmpty(value.getThumbnail().getUrl())) {
            ImageCDNUrl imageCDNUrl = new ImageCDNUrl(Math.max(width, 0), Math.max(height, 0), q, null, value.getThumbnail().getUrl());
            return imageCDNUrl.toString();
        }
        return null;
    }

    public String getCoverUrl() {
        if (TYPE_CHANNEL.equals(valueType) && value != null && value instanceof ChannelMetadata && ((ChannelMetadata) value).getCover() != null) {
            return ((ChannelMetadata) value).getCover().getUrl();
        }
        return null;
    }

    public String getFirstCharacter() {
        if (name != null) {
            return name.startsWith("@") ? name.substring(1) : name;
        }
        return "";
    }

    public String getFirstTag() {
        if (value != null && value.tags != null && value.tags.size() > 0) {
            return value.tags.get(0);
        }
        return null;
    }

    public String getDescription() {
        return (value != null) ? value.getDescription() : null;
    }

    public String getWebsiteUrl() {
        return (value instanceof ChannelMetadata) ? ((ChannelMetadata) value).getWebsiteUrl() : null;
    }

    public String getEmail() {
        return (value instanceof ChannelMetadata) ? ((ChannelMetadata) value).getEmail() : null;
    }

    public String getPublisherName() {
        if (signingChannel != null) {
            return signingChannel.getName();
        }
        return "Anonymous";
    }

    public String getPublisherTitle() {
        if (signingChannel != null) {
            return Helper.isNullOrEmpty(signingChannel.getTitle()) ? signingChannel.getName() : signingChannel.getTitle();
        }
        return "Anonymous";
    }


    public List<String> getTags() {
        return (value != null && value.getTags() != null) ? new ArrayList<>(value.getTags()) : new ArrayList<>();
    }

    public List<Tag> getTagObjects() {
        List<Tag> tags = new ArrayList<>();
        if (value != null && value.getTags() != null) {
            for (String value : value.getTags()) {
                tags.add(new Tag(value));
            }
        }
        return tags;
    }

    public String getTitle() {
        return (value != null) ? value.getTitle() : null;
    }
    public String getTitleOrName() {
        return (value != null) ? value.getTitle() : getName();
    }

    public long getDuration() {
        if (value instanceof StreamMetadata) {
            StreamMetadata metadata = (StreamMetadata) value;
            if (STREAM_TYPE_VIDEO.equalsIgnoreCase(metadata.getStreamType()) && metadata.getVideo() != null) {
                return metadata.getVideo().getDuration();
            } else if (STREAM_TYPE_AUDIO.equalsIgnoreCase(metadata.getStreamType()) && metadata.getAudio() != null) {
                return metadata.getAudio().getDuration();
            }
        }

        return 0;
    }

    public static Claim fromViewHistory(ViewHistory viewHistory) {
        // only for stream claims
        Claim claim = new Claim();
        claim.setClaimId(viewHistory.getClaimId());
        claim.setName(viewHistory.getClaimName());
        claim.setValueType(TYPE_STREAM);
        claim.setPermanentUrl(viewHistory.getUri().toString());
        claim.setDevice(viewHistory.getDevice());
        claim.setConfirmations(1);

        StreamMetadata value = new StreamMetadata();
        value.setTitle(viewHistory.getTitle());
        value.setReleaseTime(viewHistory.getReleaseTime());
        if (!Helper.isNullOrEmpty(viewHistory.getThumbnailUrl())) {
            Resource thumbnail = new Resource();
            thumbnail.setUrl(viewHistory.getThumbnailUrl());
            value.setThumbnail(thumbnail);
        }
        if (viewHistory.getCost() != null && viewHistory.getCost().doubleValue() > 0) {
            Fee fee = new Fee();
            fee.setAmount(String.valueOf(viewHistory.getCost().doubleValue()));
            fee.setCurrency(viewHistory.getCurrency());
            value.setFee(fee);
        }

        claim.setValue(value);

        if (!Helper.isNullOrEmpty(viewHistory.getPublisherClaimId())) {
            Claim signingChannel = new Claim();
            signingChannel.setClaimId(viewHistory.getPublisherClaimId());
            signingChannel.setName(viewHistory.getPublisherName());

            LbryUri channelUrl = LbryUri.tryParse(String.format("%s#%s", signingChannel.getName(), signingChannel.getClaimId()));
            signingChannel.setPermanentUrl(channelUrl != null ? channelUrl.toString() : null);
            if (!Helper.isNullOrEmpty(viewHistory.getPublisherTitle())) {
                GenericMetadata channelValue = new GenericMetadata();
                channelValue.setTitle(viewHistory.getPublisherTitle());
                signingChannel.setValue(channelValue);
            }
            claim.setSigningChannel(signingChannel);
        }

        return claim;
    }

    public static Claim fromJSONObject(JSONObject claimObject) {
        Claim claim = null;
        String claimJson = claimObject.toString();
        Type type = new TypeToken<Claim>(){}.getType();
        Type streamMetadataType = new TypeToken<StreamMetadata>(){}.getType();
        Type channelMetadataType = new TypeToken<ChannelMetadata>(){}.getType();

        Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
        claim = gson.fromJson(claimJson, type);

        try {
            String valueType = claim.getValueType();
            // Specific value type parsing
            if (TYPE_REPOST.equalsIgnoreCase(valueType)) {
                JSONObject repostedClaimObject = claimObject.getJSONObject("reposted_claim");
                claim.setRepostedClaim(Claim.fromJSONObject(repostedClaimObject));
            } else {
                if (claimObject.has("value")) {
                    JSONObject value = claimObject.getJSONObject("value");
                    String valueJson = value.toString();
                    if (TYPE_STREAM.equalsIgnoreCase(valueType)) {
                        claim.setValue(gson.fromJson(valueJson, streamMetadataType));
                    } else if (TYPE_CHANNEL.equalsIgnoreCase(valueType)) {
                        claim.setValue(gson.fromJson(valueJson, channelMetadataType));
                    } else if (TYPE_COLLECTION.equalsIgnoreCase(valueType)) {
                        JSONArray claims = value.getJSONArray("claims");
                        List<String> ids = new ArrayList<>(claims.length());

                        for (int i = 0; i < claims.length(); i++) {
                            ids.add(claims.getString(i));
                        }
                        claim.setClaimIds(ids);
                    }
                }
            }

            JSONObject metaObject = claimObject.getJSONObject("meta");
            String metaJson = metaObject.toString();
            claim.setMeta(gson.fromJson(metaJson, Meta.class));
        } catch (JSONException ex) {
            ex.printStackTrace();
            // pass
        }

        return claim;
    }

    /**
     * Create a new Claim instance which only sets specific fileds for livestreamed claims
     * @param claimId - The claimId to used for the claim
     * @param liveUrl - The url which should be used for connecting to the livestream
     * @param livestreamViewers - The amount of viewers currently watching the stream
     * @return A new Claim object
     */
    public static Claim fromLiveStatus(String claimId, String liveUrl, int livestreamViewers) {
        Claim claim = new Claim();
        claim.setClaimId(claimId);
        claim.setLivestreamUrl(liveUrl);
        claim.setLivestreamViewers(livestreamViewers);
        return claim;
    }

    @SuppressLint("SimpleDateFormat")
    public static Claim fromSearchJSONObject(JSONObject searchResultObject) {
        Claim claim = new Claim();
        LbryUri claimUri = new LbryUri();
        try {
            claim.setClaimId(searchResultObject.getString("claimId"));
            claim.setName(searchResultObject.getString("name"));
            claim.setConfirmations(1);

            if (claim.getName().startsWith("@")) {
                claimUri.setChannelClaimId(claim.getClaimId());
                claimUri.setChannelName(claim.getName());
                claim.setValueType(TYPE_CHANNEL);
            } else {
                claimUri.setStreamClaimId(claim.getClaimId());
                claimUri.setStreamName(claim.getName());
                claim.setValueType((!searchResultObject.isNull("value_type") && searchResultObject.getString("value_type").equalsIgnoreCase("collection")) ? TYPE_COLLECTION : TYPE_STREAM);
            }

            int duration = searchResultObject.isNull("duration") ? 0 : searchResultObject.getInt("duration");
            long feeAmount = searchResultObject.isNull("fee") ? 0 : searchResultObject.getLong("fee");
            String releaseTimeString = !searchResultObject.isNull("release_time") ? searchResultObject.getString("release_time") : null;
            long releaseTime = 0;
            try {
                if (releaseTimeString != null) {
                    Date releaseTimeAsDate = new SimpleDateFormat(RELEASE_TIME_DATE_FORMAT).parse(releaseTimeString);

                    if (releaseTimeAsDate!= null) {
                        releaseTime = Double.valueOf(releaseTimeAsDate.getTime() / 1000.0).longValue();
                    }
                }
            } catch (ParseException ex) {
                // pass
            }

            GenericMetadata metadata = (duration > 0 || releaseTime > 0 || feeAmount > 0) ? new StreamMetadata() : new GenericMetadata();
            metadata.setTitle(searchResultObject.getString("title"));
            if (metadata instanceof StreamMetadata) {
                StreamInfo streamInfo = new StreamInfo();
                if (duration > 0) {
                    // assume stream type video
                    ((StreamMetadata) metadata).setStreamType(STREAM_TYPE_VIDEO);
                    streamInfo.setDuration(duration);
                }

                Fee fee = null;
                if (feeAmount > 0) {
                    fee = new Fee();
                    fee.setAmount(String.valueOf(new BigDecimal(String.valueOf(feeAmount)).divide(new BigDecimal(100000000))));
                    fee.setCurrency("LBC");
                }

                ((StreamMetadata) metadata).setFee(fee);
                ((StreamMetadata) metadata).setVideo(streamInfo);
                ((StreamMetadata) metadata).setReleaseTime(releaseTime);
            }
            claim.setValue(metadata);

            if (!searchResultObject.isNull("thumbnail_url")) {
                Resource thumbnail = new Resource();
                thumbnail.setUrl(searchResultObject.getString("thumbnail_url"));
                claim.getValue().setThumbnail(thumbnail);
            }

            if (!searchResultObject.isNull("channel_claim_id") && !searchResultObject.isNull("channel")) {
                Claim signingChannel = new Claim();
                signingChannel.setClaimId(searchResultObject.getString("channel_claim_id"));
                signingChannel.setName(searchResultObject.getString("channel"));
                LbryUri channelUri = new LbryUri();
                channelUri.setChannelClaimId(signingChannel.getClaimId());
                channelUri.setChannelName(signingChannel.getName());
                signingChannel.setPermanentUrl(channelUri.toString());

                claim.setSigningChannel(signingChannel);
            }
        } catch (JSONException ex) {
            // pass
        }

        claim.setPermanentUrl(claimUri.toString());

        return claim;
    }

    public void setLiked(boolean l) {
        this.liked = l;
    }

    public void setDisliked(boolean d) {
        this.disliked = d;
    }

    @Data
    public static class Meta {
        private long activationHeight;
        private int claimsInChannel;
        private int creationHeight;
        private int creationTimestamp;
        private String effectiveAmount;
        private long expirationHeight;
        private boolean isControlling;
        private String supportAmount;
        private int reposted;
        private double trendingGlobal;
        private double trendingGroup;
        private double trendingLocal;
        private double trendingMixed;
    }

    @Data
    public static class GenericMetadata {
        private String title;
        private String description;
        private Resource thumbnail;
        private List<String> languages;
        private List<String> tags;
        private List<Location> locations;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ChannelMetadata extends GenericMetadata {
        private String publicKey;
        private String publicKeyId;
        private Resource cover;
        private String email;
        private String websiteUrl;
        private List<String> featured;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class StreamMetadata extends GenericMetadata {
        private String license;
        private String licenseUrl;
        private long releaseTime;
        private String author;
        private Fee fee;
        private String streamType; // video | audio | image | software
        private Source source;
        private StreamInfo video;
        private StreamInfo audio;
        private StreamInfo image;
        private StreamInfo software;

        @Data
        public static class Source {
            private String sdHash;
            private String mediaType;
            private String hash;
            private String name;
            private long size;
        }
    }

    // only support "url" for now
    @Data
    public static class Resource {
        private String url;
    }

    /**
     * Object to be instantiated. In order to get the URL to the CDN, call toString() on it
     */
    static class ImageCDNUrl {
        private String appendedPath = "";

        public ImageCDNUrl(int width, int height, int quality, @Nullable String format, String thumbnailUrl) {
            if (width != 0 && height != 0)
                appendedPath = "s:".concat(String.valueOf(width)).concat(":").concat(String.valueOf(height)).concat("/");

            appendedPath = appendedPath.concat("quality:").concat(String.valueOf(quality)).concat("/");

            appendedPath = appendedPath.concat("plain/").concat(thumbnailUrl);

            if (format != null) {
                appendedPath = appendedPath.concat("@").concat(format);
            }
        }

        @Override
        public String toString() {
            String url = "https://thumbnails.odysee.com/optimize/";
            return url.concat(appendedPath);
        }
    }
    @Data
    public static class StreamInfo {
        private long duration; // video / audio
        private long height; // video / image
        private long width; // video / image
        private String os; // software
    }
}
