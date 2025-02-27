package com.odysee.app.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import com.odysee.app.utils.Helper;
import lombok.Data;

@Data
public class Comment implements Comparable<Comment> {
    public static final int MAX_LENGTH = 2000;

    private Claim poster;
    private String claimId;
    private long timestamp;
    private String channelId;
    private String channelName, text, id, parentId;
    private Reactions reactions;

    public Comment(String channelId, String channelName, String text, String id, String parentId) {
        this.channelId = channelId;
        this.channelName = channelName;
        this.text = text;
        this.id = id;
        this.parentId = parentId;
    }

    public Comment() {

    }
    public static Comment fromJSONObject(JSONObject jsonObject) {
        try {
            String parentId = null;
            if (jsonObject.has("parent_id")) {
                parentId = jsonObject.getString("parent_id");
            }

            Comment comment = new Comment(
                    Helper.getJSONString("channel_id", null, jsonObject),
                    jsonObject.getString("channel_name"),
                    jsonObject.getString("comment"),
                    jsonObject.getString("comment_id"),
                    parentId
            );
            comment.setClaimId(Helper.getJSONString("claim_id", null, jsonObject));
            comment.setTimestamp(Helper.getJSONLong("timestamp", 0, jsonObject));
            return comment;
        } catch (JSONException ex) {
            return null;
        }
    }

    @Override
    public int compareTo(Comment comment) {
        return (int)(this.getTimestamp() - comment.getTimestamp());
    }
}
