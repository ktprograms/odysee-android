package com.odysee.app.ui.findcontent;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.odysee.app.MainActivity;
import com.odysee.app.R;
import com.odysee.app.adapter.ChannelFilterListAdapter;
import com.odysee.app.adapter.ClaimListAdapter;
import com.odysee.app.adapter.SuggestedChannelGridAdapter;
import com.odysee.app.callable.ChannelLiveStatus;
import com.odysee.app.callable.Search;
import com.odysee.app.dialog.ContentFromDialogFragment;
import com.odysee.app.dialog.ContentSortDialogFragment;
import com.odysee.app.dialog.DiscoverDialogFragment;
import com.odysee.app.exceptions.LbryUriException;
import com.odysee.app.listener.DownloadActionListener;
import com.odysee.app.model.Claim;
import com.odysee.app.model.LbryFile;
import com.odysee.app.model.lbryinc.Subscription;
import com.odysee.app.tasks.claim.ClaimSearchResultHandler;
import com.odysee.app.tasks.lbryinc.ChannelSubscribeTask;
import com.odysee.app.tasks.claim.ClaimListResultHandler;
import com.odysee.app.tasks.claim.ClaimSearchTask;
import com.odysee.app.tasks.claim.ResolveTask;
import com.odysee.app.listener.ChannelItemSelectionListener;
import com.odysee.app.tasks.lbryinc.FetchSubscriptionsTask;
import com.odysee.app.ui.BaseFragment;
import com.odysee.app.utils.ContentSources;
import com.odysee.app.utils.Helper;
import com.odysee.app.utils.Lbry;
import com.odysee.app.utils.LbryAnalytics;
import com.odysee.app.utils.LbryUri;
import com.odysee.app.utils.Lbryio;
import com.odysee.app.utils.Predefined;

public class FollowingFragment extends BaseFragment implements
        FetchSubscriptionsTask.FetchSubscriptionsHandler,
        ChannelItemSelectionListener,
        DownloadActionListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    public static boolean resetClaimSearchContent;
    private static final int SUGGESTED_PAGE_SIZE = 45;
    private static final int MIN_SUGGESTED_SUBSCRIBE_COUNT = 5;

    private DiscoverDialogFragment discoverDialog;
    private List<String> excludeChannelIdsForDiscover;
    private MaterialButton suggestedDoneButton;
    private TextView titleView;
    private TextView infoView;
    private RecyclerView horizontalChannelList;
    private RecyclerView suggestedChannelGrid;
    private RecyclerView contentList;
    private ProgressBar bigContentLoading;
    private ProgressBar contentLoading;
    private ProgressBar channelListLoading;
    private View layoutSortContainer;
    private View filterLink;
    private View sortLink;
    private TextView sortLinkText;
    private View contentFromLink;
    private TextView contentFromLinkText;
    private View discoverLink;
    private int currentSortBy;
    private int currentContentFrom;
    private String contentReleaseTime;
    private List<String> contentSortOrder;
    private boolean contentClaimSearchLoading = false;
    private boolean suggestedClaimSearchLoading = false;
    private View noContentView;
    private boolean subscriptionsShown;

    private View findFollowingContainer;

    private final List<Integer> queuedContentPages = new ArrayList<>();
    private final List<Integer> queuedSuggestedPages = new ArrayList<>();

    private int currentSuggestedPage = 0;
    private int currentClaimSearchPage;
    private boolean suggestedHasReachedEnd;
    private boolean contentHasReachedEnd;
    private boolean contentPendingFetch = false;
    private int numSuggestedSelected;

    // adapters
    private SuggestedChannelGridAdapter suggestedChannelAdapter;
    private ChannelFilterListAdapter channelFilterListAdapter;
    private ClaimListAdapter contentListAdapter;

    private List<String> channelIds;
    private List<String> channelUrls;
    private List<Subscription> subscriptionsList;
    private List<Claim> suggestedChannels;
    private ClaimSearchTask suggestedChannelClaimSearchTask;
    private ClaimSearchTask contentClaimSearchTask;
    private boolean loadingSuggested;
    private boolean loadingContent;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ExecutorService fixedExecutor;
    Map<String, JSONObject> liveChannels;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_following, container, false);

        // Following page is sorted by new by default, past week if sort is top
        currentSortBy = ContentSortDialogFragment.ITEM_SORT_BY_NEW;
        currentContentFrom = ContentFromDialogFragment.ITEM_FROM_PAST_WEEK;

        findFollowingContainer = root.findViewById(R.id.find_following_container);
        titleView = root.findViewById(R.id.find_following_page_title);
        infoView = root.findViewById(R.id.following_page_info);
        horizontalChannelList = root.findViewById(R.id.following_channel_list);
        layoutSortContainer = root.findViewById(R.id.following_filter_container);
        sortLink = root.findViewById(R.id.following_sort_link);
        sortLinkText = root.findViewById(R.id.following_sort_link_text);
        filterLink = root.findViewById(R.id.filter_by_channel_link);
        contentFromLink = root.findViewById(R.id.following_time_link);
        contentFromLinkText = root.findViewById(R.id.following_time_link_text);
        suggestedChannelGrid = root.findViewById(R.id.following_suggested_grid);
        suggestedDoneButton = root.findViewById(R.id.following_suggested_done_button);
        contentList = root.findViewById(R.id.following_content_list);
        bigContentLoading = root.findViewById(R.id.following_main_progress);
        contentLoading = root.findViewById(R.id.following_content_progress);
        channelListLoading = root.findViewById(R.id.following_channel_load_progress);
        discoverLink = root.findViewById(R.id.following_discover_link);
        noContentView = root.findViewById(R.id.following_no_claim_search_content);

        Context context = getContext();
        GridLayoutManager glm = new GridLayoutManager(context, 3);
        suggestedChannelGrid.setLayoutManager(glm);
        suggestedChannelGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (suggestedClaimSearchLoading) {
                    return;
                }

                GridLayoutManager lm = (GridLayoutManager) recyclerView.getLayoutManager();
                if (lm != null) {
                    int visibleItemCount = lm.getChildCount();
                    int totalItemCount = lm.getItemCount();
                    int pastVisibleItems = lm.findFirstVisibleItemPosition();
                    if (pastVisibleItems + visibleItemCount >= totalItemCount) {
                        if (!suggestedHasReachedEnd) {
                            // load more
                            currentSuggestedPage++;
                            fetchSuggestedChannels();
                        }
                    }
                }
            }
        });

        LinearLayoutManager cllm = new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false);
        horizontalChannelList.setLayoutManager(cllm);

        LinearLayoutManager llm = new LinearLayoutManager(context);
        contentList.setLayoutManager(llm);
        contentList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (contentClaimSearchLoading) {
                    return;
                }
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null) {
                    int visibleItemCount = lm.getChildCount();
                    int totalItemCount = lm.getItemCount();
                    int pastVisibleItems = lm.findFirstVisibleItemPosition();
                    if (pastVisibleItems + visibleItemCount >= totalItemCount) {
                        if (!contentHasReachedEnd) {
                            // load more
                            currentClaimSearchPage++;
                            fetchClaimSearchContent();
                        }
                    }
                }
            }
        });

        suggestedDoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int selected = suggestedChannelAdapter == null ? 0 : suggestedChannelAdapter.getSelectedCount();
                int remaining = MIN_SUGGESTED_SUBSCRIBE_COUNT - selected;
                if (remaining == MIN_SUGGESTED_SUBSCRIBE_COUNT) {
                    showMessage(R.string.select_five_subscriptions);
                } else {
                    fetchSubscriptions();
                    showSubscribedContent();
                    fetchAndResolveChannelList();
                }
            }
        });

        if (context != null) {
            SharedPreferences sharedpreferences = context.getSharedPreferences("lbry_shared_preferences", Context.MODE_PRIVATE);
            Helper.setViewVisibility(horizontalChannelList, sharedpreferences.getBoolean("subscriptions_filter_visibility", false) ? View.VISIBLE : View.GONE);
        }

        filterLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Helper.setViewVisibility(horizontalChannelList, horizontalChannelList.getVisibility() == View.VISIBLE ? View.GONE: View.VISIBLE);
            }
        });
        sortLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ContentSortDialogFragment dialog = ContentSortDialogFragment.newInstance();
                dialog.setCurrentSortByItem(currentSortBy);
                dialog.setSortByListener(new ContentSortDialogFragment.SortByListener() {
                    @Override
                    public void onSortByItemSelected(int sortBy) {
                        onSortByChanged(sortBy);
                    }
                });

                Context context = getContext();
                if (context instanceof MainActivity) {
                    MainActivity activity = (MainActivity) context;
                    dialog.show(activity.getSupportFragmentManager(), ContentSortDialogFragment.TAG);
                }
            }
        });
        contentFromLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ContentFromDialogFragment dialog = ContentFromDialogFragment.newInstance();
                dialog.setCurrentFromItem(currentContentFrom);
                dialog.setContentFromListener(new ContentFromDialogFragment.ContentFromListener() {
                    @Override
                    public void onContentFromItemSelected(int contentFromItem) {
                        onContentFromChanged(contentFromItem);
                    }
                });
                Context context = getContext();
                if (context instanceof MainActivity) {
                    MainActivity activity = (MainActivity) context;
                    dialog.show(activity.getSupportFragmentManager(), ContentFromDialogFragment.TAG);
                }
            }
        });
        discoverLink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Helper.setViewEnabled(discoverLink, false);
                buildChannelIdsAndUrls();
                currentSuggestedPage = 1;
                discoverDialog = DiscoverDialogFragment.newInstance();
                discoverDialog.setAdapter(suggestedChannelAdapter);
                discoverDialog.setDialogActionsListener(new DiscoverDialogFragment.DiscoverDialogListener() {
                    @Override
                    public void onScrollEndReached() {
                        if (suggestedClaimSearchLoading) {
                            return;
                        }
                        currentSuggestedPage++;
                        fetchSuggestedChannels();
                    }
                    @Override
                    public void onCancel() {
                        discoverDialog = null;
                        excludeChannelIdsForDiscover = null;
                        if (suggestedChannelAdapter != null) {
                            suggestedChannelAdapter.clearItems();
                        }
                        Helper.setViewEnabled(discoverLink, true);
                    }
                    @Override
                    public void onResume() {
                        if (suggestedChannelAdapter == null || suggestedChannelAdapter.getItemCount() == 0) {
                            discoverDialog.setLoading(true);
                            fetchSuggestedChannels();
                        }
                    }
                });

                Context context = getContext();
                if (context instanceof MainActivity) {
                    MainActivity activity = (MainActivity) context;
                    discoverDialog.show(activity.getSupportFragmentManager(), DiscoverDialogFragment.TAG);
                }
            }
        });

        return root;
    }

    private void onContentFromChanged(int contentFrom) {
        currentContentFrom = contentFrom;

        // rebuild options and search
        updateContentFromLinkText();
        contentReleaseTime = Helper.buildReleaseTime(currentContentFrom);
        fetchClaimSearchContent(true);
    }

    private void onSortByChanged(int sortBy) {
        currentSortBy = sortBy;

        // rebuild options and search
        Helper.setViewVisibility(contentFromLink, currentSortBy == ContentSortDialogFragment.ITEM_SORT_BY_TOP ? View.VISIBLE : View.GONE);
        currentContentFrom = currentSortBy == ContentSortDialogFragment.ITEM_SORT_BY_TOP ?
                (currentContentFrom == 0 ? ContentFromDialogFragment.ITEM_FROM_PAST_WEEK : currentContentFrom) : 0;

        updateSortByLinkText();
        contentSortOrder = Helper.buildContentSortOrder(currentSortBy);
        contentReleaseTime = Helper.buildReleaseTime(currentContentFrom);
        fetchClaimSearchContent(true);
    }

    private void updateSortByLinkText() {
        int stringResourceId = -1;
        switch (currentSortBy) {
            case ContentSortDialogFragment.ITEM_SORT_BY_NEW: default: stringResourceId = R.string.new_text; break;
            case ContentSortDialogFragment.ITEM_SORT_BY_TOP: stringResourceId = R.string.top; break;
            case ContentSortDialogFragment.ITEM_SORT_BY_TRENDING: stringResourceId = R.string.trending; break;
        }

        Helper.setViewText(sortLinkText, stringResourceId);
    }

    private void updateContentFromLinkText() {
        int stringResourceId = -1;
        switch (currentContentFrom) {
            case ContentFromDialogFragment.ITEM_FROM_PAST_24_HOURS: stringResourceId = R.string.past_24_hours; break;
            case ContentFromDialogFragment.ITEM_FROM_PAST_WEEK: default: stringResourceId = R.string.past_week; break;
            case ContentFromDialogFragment.ITEM_FROM_PAST_MONTH: stringResourceId = R.string.past_month; break;
            case ContentFromDialogFragment.ITEM_FROM_PAST_YEAR: stringResourceId = R.string.past_year; break;
            case ContentFromDialogFragment.ITEM_FROM_ALL_TIME: stringResourceId = R.string.all_time; break;
        }

        Helper.setViewText(contentFromLinkText, stringResourceId);
    }

    public void onResume() {
        super.onResume();
        Context context = getContext();
        Helper.setWunderbarValue(null, context);
        PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(this);
        if (context instanceof MainActivity) {
            MainActivity activity = (MainActivity) context;
            LbryAnalytics.setCurrentScreen(activity, "Subscriptions", "Subscriptions");
            activity.addDownloadActionListener(this);
        }

        // check if subscriptions exist
        if (suggestedChannelAdapter != null) {
            showSuggestedChannels();
            if (suggestedChannelGrid != null) {
                suggestedChannelGrid.setAdapter(suggestedChannelAdapter);
            }
        }

        liveChannels = null;
        if (Lbryio.subscriptions != null && Lbryio.subscriptions.size() > 0) {
            fetchLoadedSubscriptions(true);
        } else {
            fetchSubscriptions();
        }
    }
    public void onPause() {
        Context context = getContext();
        if (context instanceof MainActivity) {
            ((MainActivity) context).removeDownloadActionListener(this);
            PreferenceManager.getDefaultSharedPreferences(context).unregisterOnSharedPreferenceChangeListener(this);

            // Store current state of the channel filter as a preference
            SharedPreferences sharedpreferences = context.getSharedPreferences("lbry_shared_preferences", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedpreferences.edit();
            editor.putBoolean("subscriptions_filter_visibility", horizontalChannelList.getVisibility() == View.VISIBLE);
            editor.apply();
        }
        super.onPause();
    }
    public void fetchLoadedSubscriptions(boolean showSubscribed) {
        subscriptionsList = new ArrayList<>(Lbryio.subscriptions);
        buildChannelIdsAndUrls();
        if (Lbryio.cacheResolvedSubscriptions.size() > 0) {
            updateChannelFilterListAdapter(Lbryio.cacheResolvedSubscriptions, resetClaimSearchContent);
        } else {
            fetchAndResolveChannelList();
        }

        fetchClaimSearchContent(resetClaimSearchContent);
        resetClaimSearchContent = false;
        if (showSubscribed && subscriptionsList.size() > 0) {
            showSubscribedContent();
        }
    }

    public void loadFollowing() {
        // wrapper to just re-fetch subscriptions (upon user sign in, for example)
        fetchSubscriptions();
    }

    private void fetchSubscriptions() {
        FetchSubscriptionsTask task = new FetchSubscriptionsTask(getContext(), channelListLoading, Lbryio.AUTH_TOKEN, this);
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private Map<String, Object> buildSuggestedOptions() {
        Context context = getContext();
        boolean canShowMatureContent = false;
        if (context != null) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
            canShowMatureContent = sp.getBoolean(MainActivity.PREFERENCE_KEY_SHOW_MATURE_CONTENT, false);
        }

        ContentSources.Category primaryCategory = null;
        for (ContentSources.Category category : ContentSources.DYNAMIC_CONTENT_CATEGORIES) {
            if ("PRIMARY_CONTENT".equalsIgnoreCase(category.getKey())) {
                primaryCategory = category;
                break;
            }
        }

        return Lbry.buildClaimSearchOptions(
                Claim.TYPE_CHANNEL,
                null,
                canShowMatureContent ? null : new ArrayList<>(Predefined.MATURE_TAGS),
                primaryCategory != null ? Arrays.asList(primaryCategory.getChannelIds()) : null,
                null,
                excludeChannelIdsForDiscover,
                Arrays.asList(Claim.ORDER_BY_TRENDING_MIXED),
                null,
                currentSuggestedPage == 0 ? 1 : currentSuggestedPage,
                SUGGESTED_PAGE_SIZE);
    }

    private Map<String, Object> buildContentOptions() {
        Context context = getContext();
        boolean canShowMatureContent = false;
        if (context != null) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
            canShowMatureContent = sp.getBoolean(MainActivity.PREFERENCE_KEY_SHOW_MATURE_CONTENT, false);
        }

        return Lbry.buildClaimSearchOptions(
                Arrays.asList(Claim.TYPE_STREAM, Claim.TYPE_REPOST),
                null,
                canShowMatureContent ? null : new ArrayList<>(Predefined.MATURE_TAGS),
                null,
                getChannelIds(),
                null,
                getContentSortOrder(),
                contentReleaseTime,
                0,
                0,
                currentClaimSearchPage == 0 ? 1 : currentClaimSearchPage,
                Helper.CONTENT_PAGE_SIZE);
    }

    private List<String> getChannelIds() {
        if (channelFilterListAdapter != null) {
            Claim selected = channelFilterListAdapter.getSelectedItem();
            if (selected != null && !Helper.isNullOrEmpty(selected.getClaimId())) {
                return Arrays.asList(selected.getClaimId());
            }
        }

        return channelIds;
    }

    private List<String> getContentSortOrder() {
        if (contentSortOrder == null) {
            return Arrays.asList(Claim.ORDER_BY_RELEASE_TIME);
        }
        return contentSortOrder;
    }

    private void showSuggestedChannels() {
        Helper.setViewVisibility(titleView, View.VISIBLE);
        Helper.setViewVisibility(horizontalChannelList, View.GONE);
        Helper.setViewVisibility(contentList, View.GONE);
        Helper.setViewVisibility(infoView, View.VISIBLE);
        Helper.setViewVisibility(layoutSortContainer, View.GONE);
        Helper.setViewVisibility(findFollowingContainer, View.VISIBLE);
        Helper.setViewVisibility(suggestedChannelGrid, View.VISIBLE);
        Helper.setViewVisibility(suggestedDoneButton, View.VISIBLE);

        updateSuggestedDoneButtonText();
    }

    private void showSubscribedContent() {
        subscriptionsShown = true;

        Helper.setViewVisibility(titleView, View.GONE);
        Helper.setViewVisibility(contentList, View.VISIBLE);
        Helper.setViewVisibility(infoView, View.GONE);
        Helper.setViewVisibility(layoutSortContainer, View.VISIBLE);
        Helper.setViewVisibility(filterLink, View.VISIBLE);
        Helper.setViewVisibility(findFollowingContainer, View.GONE);
        Helper.setViewVisibility(suggestedChannelGrid, View.GONE);
        Helper.setViewVisibility(suggestedDoneButton, View.GONE);
    }

    private void buildChannelIdsAndUrls() {
        channelIds = new ArrayList<>();
        channelUrls = new ArrayList<>();
        if (subscriptionsList != null) {
            for (Subscription subscription : subscriptionsList) {
                try {
                    String url = subscription.getUrl();
                    LbryUri uri = LbryUri.parse(url);
                    String claimId = uri.getClaimId();
                    if (Helper.isNullOrEmpty(claimId) || Helper.isNullOrEmpty(url)) {
                        // don't add null / empty claim IDs or URLs
                        continue;
                    }

                    channelIds.add(claimId);
                    channelUrls.add(url);
                } catch (LbryUriException ex) {
                    // pass
                }
            }
        }
        excludeChannelIdsForDiscover = new ArrayList<>(channelIds);
    }

    private void fetchAndResolveChannelList() {
        buildChannelIdsAndUrls();
        if (!channelIds.isEmpty()) {
            ResolveTask resolveSubscribedTask = new ResolveTask(channelUrls, Lbry.API_CONNECTION_STRING, channelListLoading, new ClaimListResultHandler() {
                @Override
                public void onSuccess(List<Claim> claims) {
                    updateChannelFilterListAdapter(claims, true);
                    Lbryio.cacheResolvedSubscriptions = claims;
                }

                @Override
                public void onError(Exception error) {
                    handler.postDelayed(FollowingFragment.this::fetchAndResolveChannelList, 5_000);
                }
            });
            resolveSubscribedTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

            fetchClaimSearchContent();
        }
    }

    private View getLoadingView() {
        return (contentListAdapter == null || contentListAdapter.getItemCount() == 0) ? bigContentLoading : contentLoading;
    }

    private void updateChannelFilterListAdapter(List<Claim> resolvedSubs, boolean reset) {
        Context context = getContext();
        if (channelFilterListAdapter == null && context != null) {
            channelFilterListAdapter = new ChannelFilterListAdapter(context);
            channelFilterListAdapter.setListener(new ChannelItemSelectionListener() {
                @Override
                public void onChannelItemSelected(Claim claim) {
                    if (contentClaimSearchTask != null && contentClaimSearchTask.getStatus() != AsyncTask.Status.FINISHED) {
                        contentClaimSearchTask.cancel(true);
                    }
                    if (contentListAdapter != null) {
                        contentListAdapter.clearItems();
                    }
                    currentClaimSearchPage = 1;
                    contentClaimSearchLoading = false;
                    fetchClaimSearchContent();
                }

                @Override
                public void onChannelItemDeselected(Claim claim) {

                }

                @Override
                public void onChannelSelectionCleared() {
                    if (contentClaimSearchTask != null && contentClaimSearchTask.getStatus() != AsyncTask.Status.FINISHED) {
                        contentClaimSearchTask.cancel(true);
                    }
                    if (contentListAdapter != null) {
                        contentListAdapter.clearItems();
                    }
                    currentClaimSearchPage = 1;
                    contentClaimSearchLoading = false;
                    fetchClaimSearchContent();
                }
            });
        }

        if (channelFilterListAdapter != null) {
            if (horizontalChannelList != null && horizontalChannelList.getAdapter() == null) {
                horizontalChannelList.setAdapter(channelFilterListAdapter);
            }
            if (reset) {
                channelFilterListAdapter.clearClaims();
                channelFilterListAdapter.setSelectedItem(null);
            }
            channelFilterListAdapter.addClaims(resolvedSubs);
        }
    }

    private void fetchClaimSearchContent() {
        fetchClaimSearchContent(false);
    }

    private void fetchClaimSearchContent(boolean reset) {
        if (reset && contentListAdapter != null) {
            contentListAdapter.clearItems();
            currentClaimSearchPage = 1;
            liveChannels = null;
        }

        contentClaimSearchLoading = true;
        Helper.setViewVisibility(noContentView, View.GONE);
        Map<String, Object> claimSearchOptions = buildContentOptions();

        Activity a = getActivity();

        getLoadingView().setVisibility(View.VISIBLE);

        if (fixedExecutor == null || fixedExecutor.isShutdown()) {
            fixedExecutor = Executors.newFixedThreadPool(4);
        }

        Collection<Callable<List<Claim>>> callables = new ArrayList<>(2);
        callables.add(() -> fetchActiveLivestreams());
        callables.add(() -> Lbry.claimSearch(claimSearchOptions, Lbry.API_CONNECTION_STRING));

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Future<List<Claim>>> results = fixedExecutor.invokeAll(callables);

                    List<Claim> items = new ArrayList<>();

                    for (Future<List<Claim>> f : results) {
                        if (!f.isCancelled()) {
                            List<Claim> internalItems = f.get();

                            if (internalItems != null) {
                                items.addAll(internalItems);
                            }
                        }
                    }

                    fixedExecutor.shutdown();

                    if (claimSearchOptions.containsKey("page_size")) {
                        int pageSize = Helper.parseInt(claimSearchOptions.get("page_size"), 0);
                        contentHasReachedEnd = items.size() < pageSize;
                    }

                    Date d = new Date();
                    Calendar cal = new GregorianCalendar();
                    cal.setTime(d);

                    // Remove claims with a release time in the future or not being livestreams and don't having a source
                    items.removeIf(e -> {
                        if ((!e.isHighlightLive() || !e.isLive()) && !e.hasSource()) {
                            return true;
                        }
                        Claim.GenericMetadata metadata = e.getValue();
                        return metadata instanceof Claim.StreamMetadata && (((Claim.StreamMetadata) metadata).getReleaseTime()) > (cal.getTimeInMillis() / 1000L);
                    });

                    items = Helper.filterClaimsByOutpoint(items);
                    items = Helper.filterClaimsByBlockedChannels(items, Lbryio.blockedChannels);

                    if (a != null) {
                        List<Claim> finalClaims = items;
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                getLoadingView().setVisibility(View.GONE);
                                if (contentListAdapter == null) {
                                    Context context = getContext();
                                    if (context != null) {
                                        contentListAdapter = new ClaimListAdapter(finalClaims, context);
                                        contentListAdapter.setListener(new ClaimListAdapter.ClaimListItemListener() {
                                            @Override
                                            public void onClaimClicked(Claim claim, int position) {
                                                Context context = getContext();
                                                if (context instanceof MainActivity) {
                                                    MainActivity activity = (MainActivity) context;
                                                    if (claim.getName().startsWith("@")) {
                                                        // channel claim
                                                        activity.openChannelClaim(claim);
                                                    } else {
                                                        activity.openFileClaim(claim);
                                                    }
                                                }
                                            }
                                        });
                                    }
                                } else {
                                    contentListAdapter.addItems(finalClaims);
                                }
                                if (contentList != null && contentList.getAdapter() == null) {
                                    contentList.setAdapter(contentListAdapter);
                                }

                                contentClaimSearchLoading = false;
                                checkNoContent(false);
                            }
                        });
                    }
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                } finally {
                    if (a != null) {
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                getLoadingView().setVisibility(View.GONE);
                                contentClaimSearchLoading = false;
                                checkNoContent(false);
                            }
                        });
                    }
                }
                fixedExecutor.shutdown();
            }
        });
        t.start();
    }

    /**
     *
     * @return A list of the active claims for followed channels which are currently livestreaming
     */
    private List<Claim> fetchActiveLivestreams() {
        List<Claim> mostRecentClaims = new ArrayList<>();
        Map<String, JSONObject> livestreamingChannels;
        try {
            Future<Map<String, JSONObject>> isLiveFuture = fixedExecutor.submit(new ChannelLiveStatus(getChannelIds(), false));

            livestreamingChannels = isLiveFuture.get();

            List<Claim> activeClaims = new ArrayList<>();
            if (livestreamingChannels != null) {
                List<String> activeClaimIds = new ArrayList<>();

                Iterator<Map.Entry<String, JSONObject>> it = livestreamingChannels.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, JSONObject> pair = it.next();
                    JSONObject j = pair.getValue();
                    String activeClaimId = null;
                    if (j.has("ActiveClaim")) {
                        JSONObject activeJson = (JSONObject) j.get("ActiveClaim");
                        activeClaimId = activeJson.getString("ClaimID");
                        String livestreamUrl = j.getString("VideoURL");
                        int viewersCount = j.getInt("ViewerCount");
                        System.out.println(pair.getKey() + " = " + pair.getValue());
                        if (!activeClaimId.equalsIgnoreCase("Confirming")) {
                            activeClaims.add(Claim.fromLiveStatus(activeClaimId, livestreamUrl, viewersCount));
                            activeClaimIds.add(activeClaimId);
                        }
                    }
                    it.remove(); // avoids a ConcurrentModificationException
                }

                if (activeClaimIds.size() > 0) {
                    Map<String, Object> claimSearchOptions = buildContentOptions();

                    claimSearchOptions.put("claim_type", Collections.singletonList(Claim.TYPE_STREAM));
                    claimSearchOptions.put("has_no_source", true);
                    claimSearchOptions.put("claim_ids", activeClaimIds);
                    Future<List<Claim>> mostRecentsFuture = fixedExecutor.submit(new Search(claimSearchOptions));

                    mostRecentClaims = mostRecentsFuture.get();
                }
            }

            if (mostRecentClaims.size() == 0) {
                return null;
            } else {
                mostRecentClaims.stream().forEach(c -> {
                    Claim p = activeClaims.stream().filter(g -> g.getClaimId().equalsIgnoreCase(c.getClaimId())).findFirst().orElse(null);

                    if (p != null) {
                        Claim ac = activeClaims.get(activeClaims.indexOf(p));
                        c.setHighlightLive(true);
                        c.setLive(true);
                        c.setLivestreamUrl(ac.getLivestreamUrl());
                        c.setLivestreamViewers(ac.getLivestreamViewers());
                    }
                });
            }
        } catch (InterruptedException | ExecutionException | JSONException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                cause.printStackTrace();
            }
        }

        return mostRecentClaims;
    }

    private void updateSuggestedDoneButtonText() {
        Context context = getContext();
        if (context != null) {
            int selected = suggestedChannelAdapter == null ? 0 : suggestedChannelAdapter.getSelectedCount();
            int remaining = MIN_SUGGESTED_SUBSCRIBE_COUNT - selected;
            String buttonText = remaining <= 0 ? getString(R.string.done) : getString(R.string.n_remaining, remaining);
            Helper.setViewText(suggestedDoneButton, buttonText);
        }
    }

    private void fetchSuggestedChannels() {
        if (suggestedClaimSearchLoading) {
            return;
        }

        suggestedClaimSearchLoading = true;
        if (discoverDialog != null) {
            discoverDialog.setLoading(true);
        }

        Helper.setViewVisibility(noContentView, View.GONE);
        suggestedChannelClaimSearchTask = new ClaimSearchTask(
                buildSuggestedOptions(),
                Lbry.API_CONNECTION_STRING,
                suggestedChannelAdapter == null || suggestedChannelAdapter.getItemCount() == 0 ? bigContentLoading : contentLoading,
                new ClaimSearchResultHandler() {
                    @Override
                    public void onSuccess(List<Claim> claims, boolean hasReachedEnd) {
                        suggestedHasReachedEnd = hasReachedEnd;
                        suggestedClaimSearchLoading = false;
                        if (discoverDialog != null) {
                            discoverDialog.setLoading(false);
                        }

                        if (suggestedChannelAdapter == null) {
                            suggestedChannelAdapter = new SuggestedChannelGridAdapter(claims, getContext());
                            suggestedChannelAdapter.setListener(FollowingFragment.this);
                            if (suggestedChannelGrid != null) {
                                suggestedChannelGrid.setAdapter(suggestedChannelAdapter);
                            }
                            if (discoverDialog != null) {
                                discoverDialog.setAdapter(suggestedChannelAdapter);
                            }
                        } else {
                            suggestedChannelAdapter.addClaims(claims);
                        }

                        if (discoverDialog == null || !discoverDialog.isVisible()) {
                            checkNoContent(true);
                        }
                    }

                    @Override
                    public void onError(Exception error) {
                        suggestedClaimSearchLoading = false;
                        if (discoverDialog != null) {
                            discoverDialog.setLoading(false);
                        }
                        if (discoverDialog == null || !discoverDialog.isVisible()) {
                            checkNoContent(true);
                        }
                    }
                });

        suggestedChannelClaimSearchTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // handler methods
    public void onSuccess(List<Subscription> subscriptions) {
        if (subscriptions.isEmpty()) {
            // fresh start
            // TODO: Only do this if there are no local subscriptions stored
            currentSuggestedPage = 1;
            buildSuggestedOptions();
            loadingSuggested = true;
            loadingContent = false;

            fetchSuggestedChannels();
            showSuggestedChannels();
        } else {
            Lbryio.subscriptions = subscriptions;
            subscriptionsList = new ArrayList<>(subscriptions);
            showSubscribedContent();
            fetchAndResolveChannelList();
        }
    }

    public void onError(Exception exception) {

    }

    public void onChannelItemSelected(Claim claim) {
        // subscribe
        Subscription subscription = Subscription.fromClaim(claim);
        String channelClaimId = claim.getClaimId();

        ChannelSubscribeTask task = new ChannelSubscribeTask(getContext(), channelClaimId, subscription, false, new ChannelSubscribeTask.ChannelSubscribeHandler() {
            @Override
            public void onSuccess() {
                Lbryio.addSubscription(subscription);
                Lbryio.addCachedResolvedSubscription(claim);
                resetClaimSearchContent = true;
                fetchLoadedSubscriptions(subscriptionsShown);

                saveSharedUserState();
            }

            @Override
            public void onError(Exception error) { }
        });
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        updateSuggestedDoneButtonText();
    }
    public void onChannelItemDeselected(Claim claim) {
        // unsubscribe
        Subscription subscription = Subscription.fromClaim(claim);
        String channelClaimId = claim.getClaimId();
        ChannelSubscribeTask task = new ChannelSubscribeTask(getContext(), channelClaimId, subscription, true, new ChannelSubscribeTask.ChannelSubscribeHandler() {
            @Override
            public void onSuccess() {
                Lbryio.removeSubscription(subscription);
                Lbryio.removeCachedResolvedSubscription(claim);
                resetClaimSearchContent = true;
                fetchLoadedSubscriptions(subscriptionsShown);

                saveSharedUserState();
            }

            @Override
            public void onError(Exception error) {

            }
        });
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        updateSuggestedDoneButtonText();
    }
    public void onChannelSelectionCleared() {

    }

    private void checkNoContent(boolean suggested) {
        RecyclerView.Adapter adapter = suggested ? suggestedChannelAdapter : contentListAdapter;
        boolean noContent = adapter == null || adapter.getItemCount() == 0;
        Helper.setViewVisibility(noContentView, noContent ? View.VISIBLE : View.GONE);
    }

    private void saveSharedUserState() {
        Context context = getContext();
        if (context instanceof MainActivity) {
            ((MainActivity) context).saveSharedUserState();
        }
    }

    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key.equalsIgnoreCase(MainActivity.PREFERENCE_KEY_SHOW_MATURE_CONTENT)) {
            fetchClaimSearchContent(true);
        }
    }

    public void onDownloadAction(String downloadAction, String uri, String outpoint, String fileInfoJson, double progress) {
        if ("abort".equals(downloadAction)) {
            if (contentListAdapter != null) {
                contentListAdapter.clearFileForClaimOrUrl(outpoint, uri);
            }
            return;
        }

        try {
            JSONObject fileInfo = new JSONObject(fileInfoJson);
            LbryFile claimFile = LbryFile.fromJSONObject(fileInfo);
            String claimId = claimFile.getClaimId();
            if (contentListAdapter != null) {
                contentListAdapter.updateFileForClaimByIdOrUrl(claimFile, claimId, uri);
            }
        } catch (JSONException ex) {
            // invalid file info for download
        }
    }
}
