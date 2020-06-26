package org.wordpress.android.ui.reader.adapters;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.fluxc.store.AccountStore;
import org.wordpress.android.fluxc.store.SiteStore;
import org.wordpress.android.models.ReaderPost;
import org.wordpress.android.models.ReaderPostDiscoverData;
import org.wordpress.android.models.ReaderPostList;
import org.wordpress.android.models.ReaderTag;
import org.wordpress.android.models.news.NewsItem;
import org.wordpress.android.ui.news.NewsViewHolder;
import org.wordpress.android.ui.news.NewsViewHolder.NewsCardListener;
import org.wordpress.android.ui.reader.ReaderActivityLauncher;
import org.wordpress.android.ui.reader.ReaderConstants;
import org.wordpress.android.ui.reader.ReaderInterfaces;
import org.wordpress.android.ui.reader.ReaderInterfaces.OnFollowListener;
import org.wordpress.android.ui.reader.ReaderInterfaces.ReblogActionListener;
import org.wordpress.android.ui.reader.ReaderTypes;
import org.wordpress.android.ui.reader.ReaderTypes.ReaderPostListType;
import org.wordpress.android.ui.reader.actions.ReaderActions;
import org.wordpress.android.ui.reader.actions.ReaderBlogActions;
import org.wordpress.android.ui.reader.actions.ReaderPostActions;
import org.wordpress.android.ui.reader.discover.ReaderDiscoverViewModel.ReaderCardUiState.ReaderPostUiState;
import org.wordpress.android.ui.reader.discover.ReaderPostUiStateBuilder;
import org.wordpress.android.ui.reader.discover.viewholders.ReaderPostViewHolder;
import org.wordpress.android.ui.reader.models.ReaderBlogIdPostId;
import org.wordpress.android.ui.reader.utils.ReaderXPostUtils;
import org.wordpress.android.ui.reader.views.ReaderGapMarkerView;
import org.wordpress.android.ui.reader.views.ReaderSiteHeaderView;
import org.wordpress.android.ui.reader.views.ReaderTagHeaderView;
import org.wordpress.android.ui.utils.UiHelpers;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.ColorUtils;
import org.wordpress.android.util.ContextExtensionsKt;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.GravatarUtils;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.analytics.AnalyticsUtils;
import org.wordpress.android.util.image.ImageManager;
import org.wordpress.android.util.image.ImageType;

import java.util.HashSet;

import javax.inject.Inject;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;

public class ReaderPostAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private final ImageManager mImageManager;
    private final UiHelpers mUiHelpers;
    private NewsCardListener mNewsCardListener;
    private ReaderTag mCurrentTag;
    private long mCurrentBlogId;
    private long mCurrentFeedId;
    private int mGapMarkerPosition = -1;

    private final int mPhotonWidth;
    private final int mPhotonHeight;
    private final int mAvatarSzMedium;
    private final int mAvatarSzSmall;
    private final int mMarginExtraLarge;

    private boolean mCanRequestMorePosts;
    private final boolean mIsLoggedOutReader;

    private final ReaderTypes.ReaderPostListType mPostListType;
    private final ReaderPostList mPosts = new ReaderPostList();
    private final HashSet<String> mRenderedIds = new HashSet<>();
    private NewsItem mNewsItem;

    private ReaderInterfaces.OnFollowListener mFollowListener;
    private ReaderInterfaces.OnPostSelectedListener mPostSelectedListener;
    private ReaderInterfaces.OnPostPopupListener mOnPostPopupListener;
    private ReaderInterfaces.DataLoadedListener mDataLoadedListener;
    private ReaderInterfaces.OnPostBookmarkedListener mOnPostBookmarkedListener;
    private ReaderActions.DataRequestedListener mDataRequestedListener;
    private ReaderSiteHeaderView.OnBlogInfoLoadedListener mBlogInfoLoadedListener;
    private ReblogActionListener mReblogActionListener;

    // the large "tbl_posts.text" column is unused here, so skip it when querying
    private static final boolean EXCLUDE_TEXT_COLUMN = true;
    private static final int MAX_ROWS = ReaderConstants.READER_MAX_POSTS_TO_DISPLAY;

    private static final int VIEW_TYPE_POST = 0;
    private static final int VIEW_TYPE_XPOST = 1;
    private static final int VIEW_TYPE_SITE_HEADER = 2;
    private static final int VIEW_TYPE_TAG_HEADER = 3;
    private static final int VIEW_TYPE_GAP_MARKER = 4;
    private static final int VIEW_TYPE_REMOVED_POST = 5;
    private static final int VIEW_TYPE_NEWS_CARD = 6;

    private static final long ITEM_ID_HEADER = -1L;
    private static final long ITEM_ID_GAP_MARKER = -2L;
    private static final long ITEM_ID_NEWS_CARD = -3L;

    private static final int NEWS_CARD_POSITION = 0;

    private static final float READER_FEATURED_IMAGE_ASPECT_RATIO = 16 / 9f;

    private boolean mIsMainReader = false;

    @Inject AccountStore mAccountStore;
    @Inject SiteStore mSiteStore;
    @Inject ReaderPostUiStateBuilder mReaderPostUiStateBuilder;

    /*
     * cross-post
     */
    private class ReaderXPostViewHolder extends RecyclerView.ViewHolder {
        private final ImageView mImgAvatar;
        private final ImageView mImgBlavatar;
        private final TextView mTxtTitle;
        private final TextView mTxtSubtitle;

        ReaderXPostViewHolder(View itemView) {
            super(itemView);
            View postContainer = itemView.findViewById(R.id.post_container);
            mImgAvatar = itemView.findViewById(R.id.image_avatar);
            mImgBlavatar = itemView.findViewById(R.id.image_blavatar);
            mTxtTitle = itemView.findViewById(R.id.text_title);
            mTxtSubtitle = itemView.findViewById(R.id.text_subtitle);

            postContainer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int position = getAdapterPosition();
                    ReaderPost post = getItem(position);
                    if (mPostSelectedListener != null && post != null) {
                        mPostSelectedListener.onPostSelected(post);
                    }
                }
            });
        }
    }

    private class ReaderRemovedPostViewHolder extends RecyclerView.ViewHolder {
        final View mPostContainer;

        private final ViewGroup mRemovedPostContainer;
        private final TextView mTxtRemovedPostTitle;
        private final TextView mUndoRemoveAction;

        ReaderRemovedPostViewHolder(View itemView) {
            super(itemView);
            mPostContainer = itemView.findViewById(R.id.post_container);
            mTxtRemovedPostTitle = itemView.findViewById(R.id.removed_post_title);
            mRemovedPostContainer = itemView.findViewById(R.id.removed_item_container);
            mUndoRemoveAction = itemView.findViewById(R.id.undo_remove);
        }
    }

    private class SiteHeaderViewHolder extends RecyclerView.ViewHolder {
        private final ReaderSiteHeaderView mSiteHeaderView;

        SiteHeaderViewHolder(View itemView) {
            super(itemView);
            mSiteHeaderView = (ReaderSiteHeaderView) itemView;
        }
    }

    private class TagHeaderViewHolder extends RecyclerView.ViewHolder {
        private final ReaderTagHeaderView mTagHeaderView;

        TagHeaderViewHolder(View itemView) {
            super(itemView);
            mTagHeaderView = (ReaderTagHeaderView) itemView;
        }
    }

    private class GapMarkerViewHolder extends RecyclerView.ViewHolder {
        private final ReaderGapMarkerView mGapMarkerView;

        GapMarkerViewHolder(View itemView) {
            super(itemView);
            mGapMarkerView = (ReaderGapMarkerView) itemView;
        }
    }

    @Override
    public int getItemViewType(int position) {
        int headerPosition = hasNewsCard() ? 1 : 0;
        if (position == NEWS_CARD_POSITION && hasNewsCard()) {
            return VIEW_TYPE_NEWS_CARD;
        } else if (position == headerPosition && hasSiteHeader()) {
            // first item is a ReaderSiteHeaderView
            return VIEW_TYPE_SITE_HEADER;
        } else if (position == headerPosition && hasTagHeader()) {
            // first item is a ReaderTagHeaderView
            return VIEW_TYPE_TAG_HEADER;
        } else if (position == mGapMarkerPosition) {
            return VIEW_TYPE_GAP_MARKER;
        } else {
            ReaderPost post = getItem(position);
            if (post != null && post.isXpost()) {
                return VIEW_TYPE_XPOST;
            } else if (post != null && isBookmarksList() && !post.isBookmarked) {
                return VIEW_TYPE_REMOVED_POST;
            } else {
                return VIEW_TYPE_POST;
            }
        }
    }

    @Override
    public @NonNull RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        View postView;
        switch (viewType) {
            case VIEW_TYPE_NEWS_CARD:
                return new NewsViewHolder(parent, mNewsCardListener);
            case VIEW_TYPE_SITE_HEADER:
                ReaderSiteHeaderView readerSiteHeaderView = new ReaderSiteHeaderView(context);
                readerSiteHeaderView.setOnFollowListener(mFollowListener);
                return new SiteHeaderViewHolder(readerSiteHeaderView);

            case VIEW_TYPE_TAG_HEADER:
                return new TagHeaderViewHolder(new ReaderTagHeaderView(context));

            case VIEW_TYPE_GAP_MARKER:
                return new GapMarkerViewHolder(new ReaderGapMarkerView(context));

            case VIEW_TYPE_XPOST:
                postView = LayoutInflater.from(context).inflate(R.layout.reader_cardview_xpost, parent, false);
                return new ReaderXPostViewHolder(postView);
            case VIEW_TYPE_REMOVED_POST:
                postView = LayoutInflater.from(context).inflate(R.layout.reader_cardview_removed_post, parent, false);
                return new ReaderRemovedPostViewHolder(postView);
            default:
                return new ReaderPostViewHolder(mUiHelpers, mImageManager, parent);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ReaderPostViewHolder) {
            renderPost(position, (ReaderPostViewHolder) holder);
        } else if (holder instanceof ReaderXPostViewHolder) {
            renderXPost(position, (ReaderXPostViewHolder) holder);
        } else if (holder instanceof ReaderRemovedPostViewHolder) {
            renderRemovedPost(position, (ReaderRemovedPostViewHolder) holder);
        } else if (holder instanceof SiteHeaderViewHolder) {
            SiteHeaderViewHolder siteHolder = (SiteHeaderViewHolder) holder;
            siteHolder.mSiteHeaderView.setOnBlogInfoLoadedListener(mBlogInfoLoadedListener);
            if (isDiscover()) {
                siteHolder.mSiteHeaderView.loadBlogInfo(ReaderConstants.DISCOVER_SITE_ID, 0);
            } else {
                siteHolder.mSiteHeaderView.loadBlogInfo(mCurrentBlogId, mCurrentFeedId);
            }
        } else if (holder instanceof TagHeaderViewHolder) {
            TagHeaderViewHolder tagHolder = (TagHeaderViewHolder) holder;
            tagHolder.mTagHeaderView.setCurrentTag(mCurrentTag);
        } else if (holder instanceof GapMarkerViewHolder) {
            GapMarkerViewHolder gapHolder = (GapMarkerViewHolder) holder;
            gapHolder.mGapMarkerView.setCurrentTag(mCurrentTag);
        } else if (holder instanceof NewsViewHolder) {
            ((NewsViewHolder) holder).bind(mNewsItem);
        }
    }

    private void renderXPost(int position, ReaderXPostViewHolder holder) {
        final ReaderPost post = getItem(position);
        if (post == null) {
            return;
        }

        mImageManager
                .loadIntoCircle(holder.mImgAvatar, ImageType.AVATAR,
                        GravatarUtils.fixGravatarUrl(post.getPostAvatar(), mAvatarSzSmall));

        mImageManager.loadIntoCircle(holder.mImgBlavatar, ImageType.BLAVATAR_CIRCULAR,
                GravatarUtils.fixGravatarUrl(post.getBlogImageUrl(), mAvatarSzSmall));

        holder.mTxtTitle.setText(ReaderXPostUtils.getXPostTitle(post));
        holder.mTxtSubtitle.setText(ReaderXPostUtils.getXPostSubtitleHtml(post));

        checkLoadMore(position);
    }

    private void renderRemovedPost(final int position, final ReaderRemovedPostViewHolder holder) {
        final ReaderPost post = getItem(position);
        final Context context = holder.mRemovedPostContainer.getContext();
        holder.mTxtRemovedPostTitle.setText(createTextForRemovedPostContainer(post, context));
        Drawable drawable =
                ColorUtils.INSTANCE.applyTintToDrawable(context, R.drawable.ic_undo_white_24dp,
                        ContextExtensionsKt.getColorResIdFromAttribute(context, R.attr.colorPrimary));
        holder.mUndoRemoveAction.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
        holder.mPostContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undoPostUnbookmarked(post, position);
            }
        });
    }

    private void undoPostUnbookmarked(final ReaderPost post, final int position) {
        if (!post.isBookmarked) {
            toggleBookmark(post.blogId, post.postId);
            notifyItemChanged(position);
        }
    }

    private void renderPost(final int position, final ReaderPostViewHolder holder) {
        final ReaderPost post = getItem(position);
        ReaderPostListType postListType = getPostListType();
        if (post == null) {
            return;
        }
        Context ctx = holder.getViewContext();

        Function3<Long, Long, Boolean, Unit> onBookmarkClicked = (postId, blogId, aBoolean) -> {
            toggleBookmark(post.blogId, post.postId);
            notifyItemChanged(position);
            return Unit.INSTANCE;
        };
        Function3<Long, Long, Boolean, Unit> onLikeClicked = (postId, blogId, aBoolean) -> {
            toggleLike(ctx, post);
            return Unit.INSTANCE;
        };
        Function3<Long, Long, Boolean, Unit> onReblogClicked = (postId, blogId, aBoolean) -> {
            mReblogActionListener.reblog(post);
            return Unit.INSTANCE;
        };
        Function3<Long, Long, Boolean, Unit> onCommentClicked = (postId, blogId, aBoolean) -> {
            ReaderActivityLauncher.showReaderComments(ctx, post.blogId, post.postId);
            return Unit.INSTANCE;
        };
        Function2<Long, Long, Unit> onItemClicked = (postId, blogId) -> {
            if (mPostSelectedListener != null) {
                mPostSelectedListener.onPostSelected(post);
            }
            return Unit.INSTANCE;
        };
        Function2<Long, Long, Unit> onItemRendered = (postId, blogId) -> {
            checkLoadMore(position);

            // if we haven't already rendered this post and it has a "railcar" attached to it, add it
            // to the rendered list and record the TrainTracks render event
            if (post.hasRailcar() && !mRenderedIds.contains(post.getPseudoId())) {
                mRenderedIds.add(post.getPseudoId());
                AnalyticsUtils.trackRailcarRender(post.getRailcarJson());
            }
            return Unit.INSTANCE;
        };
        Function2<Long, Long, Unit> onDiscoverSectionClicked = (postId, blogId) -> {
            ReaderPostDiscoverData discoverData = post.getDiscoverData();
            switch (discoverData.getDiscoverType()) {
                case EDITOR_PICK:
                    if (mPostSelectedListener != null) {
                        mPostSelectedListener.onPostSelected(post);
                    }
                    break;
                case SITE_PICK:
                    if (blogId != 0) {
                        ReaderActivityLauncher.showReaderBlogPreview(ctx, blogId);
                    } else if (discoverData.hasBlogUrl()) {
                        ReaderActivityLauncher.openUrl(ctx, discoverData.getBlogUrl());
                    }
                    break;
                case OTHER:
                    //noop
                    break;
            }
            return Unit.INSTANCE;
        };
        Function3<Long, Long, View, Unit> onMoreButtonClicked = (postId, blogId, view) -> {
            if (mOnPostPopupListener != null) {
                mOnPostPopupListener.onShowPostPopup(view, post);
            }
            return Unit.INSTANCE;
        };

        Function2<Long, Long, Unit> onVideoOverlayClicked = (postId, blogId) -> {
            ReaderActivityLauncher.showReaderBlogPreview(ctx, post);
            return Unit.INSTANCE;
        };

        Function2<Long, Long, Unit> onPostHeaderClicked = (postId, blogId) -> {
            ReaderActivityLauncher.showReaderBlogPreview(ctx, post);
            return Unit.INSTANCE;
        };

        ReaderPostUiState uiState = mReaderPostUiStateBuilder
                .mapPostToUiState(
                        post,
                        mPhotonWidth,
                        mPhotonHeight,
                        postListType,
                        isBookmarksList(),
                        onBookmarkClicked,
                        onLikeClicked,
                        onReblogClicked,
                        onCommentClicked,
                        onItemClicked,
                        onItemRendered,
                        onDiscoverSectionClicked,
                        onMoreButtonClicked,
                        onVideoOverlayClicked,
                        onPostHeaderClicked
                );
        holder.onBind(uiState);
    }

    /*
     * if we're nearing the end of the posts, fire request to load more
     */
    private void checkLoadMore(int position) {
        if (mCanRequestMorePosts
            && mDataRequestedListener != null
            && (position >= getItemCount() - 1)) {
            mDataRequestedListener.onRequestData();
        }
    }

    // ********************************************************************************************

    public ReaderPostAdapter(
            Context context,
            ReaderPostListType postListType,
            ImageManager imageManager,
            UiHelpers uiHelpers,
            boolean isMainReader
    ) {
        super();
        ((WordPress) context.getApplicationContext()).component().inject(this);
        this.mImageManager = imageManager;
        mPostListType = postListType;
        mUiHelpers = uiHelpers;
        mAvatarSzMedium = context.getResources().getDimensionPixelSize(R.dimen.avatar_sz_medium);
        mAvatarSzSmall = context.getResources().getDimensionPixelSize(R.dimen.avatar_sz_small);
        mMarginExtraLarge = context.getResources().getDimensionPixelSize(R.dimen.margin_extra_large);
        mIsLoggedOutReader = !mAccountStore.hasAccessToken();
        mIsMainReader = isMainReader;

        int displayWidth = DisplayUtils.getDisplayPixelWidth(context);
        int cardMargin = context.getResources().getDimensionPixelSize(R.dimen.reader_card_margin);
        mPhotonWidth = displayWidth - (cardMargin * 2);
        mPhotonHeight = (int) (mPhotonWidth / READER_FEATURED_IMAGE_ASPECT_RATIO);

        setHasStableIds(true);
    }

    private boolean hasHeader() {
        return hasSiteHeader() || hasTagHeader();
    }

    private boolean hasSiteHeader() {
        return !mIsMainReader && (isDiscover() || getPostListType() == ReaderTypes.ReaderPostListType.BLOG_PREVIEW);
    }

    private boolean hasTagHeader() {
        return !mIsMainReader && (mCurrentTag != null && mCurrentTag.isTagTopic() && !isEmpty());
    }

    private boolean isDiscover() {
        return mCurrentTag != null && mCurrentTag.isDiscover();
    }

    public void setOnFollowListener(OnFollowListener listener) {
        mFollowListener = listener;
    }

    public void setReblogActionListener(ReblogActionListener reblogActionListener) {
        mReblogActionListener = reblogActionListener;
    }

    public void setOnPostSelectedListener(ReaderInterfaces.OnPostSelectedListener listener) {
        mPostSelectedListener = listener;
    }

    public void setOnDataLoadedListener(ReaderInterfaces.DataLoadedListener listener) {
        mDataLoadedListener = listener;
    }

    public void setOnPostBookmarkedListener(ReaderInterfaces.OnPostBookmarkedListener listener) {
        mOnPostBookmarkedListener = listener;
    }

    public void setOnDataRequestedListener(ReaderActions.DataRequestedListener listener) {
        mDataRequestedListener = listener;
    }

    public void setOnPostPopupListener(ReaderInterfaces.OnPostPopupListener onPostPopupListener) {
        mOnPostPopupListener = onPostPopupListener;
    }

    public void setOnBlogInfoLoadedListener(ReaderSiteHeaderView.OnBlogInfoLoadedListener listener) {
        mBlogInfoLoadedListener = listener;
    }

    public void setOnNewsCardListener(NewsCardListener newsCardListener) {
        this.mNewsCardListener = newsCardListener;
    }

    private ReaderTypes.ReaderPostListType getPostListType() {
        return (mPostListType != null ? mPostListType : ReaderTypes.DEFAULT_POST_LIST_TYPE);
    }

    // used when the viewing tagged posts
    public void setCurrentTag(ReaderTag tag) {
        if (!ReaderTag.isSameTag(tag, mCurrentTag)) {
            mCurrentTag = tag;
            mRenderedIds.clear();
            reload();
        }
    }

    public boolean isCurrentTag(ReaderTag tag) {
        return ReaderTag.isSameTag(tag, mCurrentTag);
    }

    // used when the list type is ReaderPostListType.BLOG_PREVIEW
    public void setCurrentBlogAndFeed(long blogId, long feedId) {
        if (blogId != mCurrentBlogId || feedId != mCurrentFeedId) {
            mCurrentBlogId = blogId;
            mCurrentFeedId = feedId;
            mRenderedIds.clear();
            reload();
        }
    }

    public void clear() {
        mGapMarkerPosition = -1;
        if (!mPosts.isEmpty()) {
            mPosts.clear();
            notifyDataSetChanged();
        }
    }

    public void refresh() {
        loadPosts();
    }

    /*
     * same as refresh() above but first clears the existing posts
     */
    public void reload() {
        clear();
        loadPosts();
    }

    public void removePostsInBlog(long blogId) {
        int numRemoved = 0;
        ReaderPostList postsInBlog = mPosts.getPostsInBlog(blogId);
        for (ReaderPost post : postsInBlog) {
            int index = mPosts.indexOfPost(post);
            if (index > -1) {
                numRemoved++;
                mPosts.remove(index);
            }
        }
        if (numRemoved > 0) {
            notifyDataSetChanged();
        }
    }

    private void loadPosts() {
        if (mIsTaskRunning) {
            AppLog.w(AppLog.T.READER, "reader posts task already running");
            return;
        }
        new LoadPostsTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private ReaderPost getItem(int position) {
        if (position == NEWS_CARD_POSITION && hasNewsCard()) {
            return null;
        }
        if (position == getHeaderPosition() && hasHeader()) {
            return null;
        }
        if (position == mGapMarkerPosition) {
            return null;
        }

        int arrayPos = position - getItemPositionOffset();

        if (mGapMarkerPosition > -1 && position > mGapMarkerPosition) {
            arrayPos--;
        }

        if (mPosts.size() <= arrayPos) {
            AppLog.d(T.READER, "Trying to read an element out of bounds of the posts list");
            return null;
        }

        return mPosts.get(arrayPos);
    }

    private int getItemPositionOffset() {
        int newsCardOffset = hasNewsCard() ? 1 : 0;
        int headersOffset = hasHeader() ? 1 : 0;
        return newsCardOffset + headersOffset;
    }

    private int getHeaderPosition() {
        int headerPosition = hasNewsCard() ? 1 : 0;
        return hasHeader() ? headerPosition : -1;
    }

    @Override
    public int getItemCount() {
        int size = mPosts.size();
        if (mGapMarkerPosition != -1) {
            size++;
        }
        if (hasHeader()) {
            size++;
        }
        if (hasNewsCard()) {
            size++;
        }
        return size;
    }

    public boolean isEmpty() {
        return (mPosts == null || mPosts.size() == 0);
    }

    private boolean isBookmarksList() {
        return (getPostListType() == ReaderPostListType.TAG_FOLLOWED
                && (mCurrentTag != null && mCurrentTag.isBookmarked()));
    }

    @Override
    public long getItemId(int position) {
        switch (getItemViewType(position)) {
            case VIEW_TYPE_TAG_HEADER:
            case VIEW_TYPE_SITE_HEADER:
                return ITEM_ID_HEADER;
            case VIEW_TYPE_GAP_MARKER:
                return ITEM_ID_GAP_MARKER;
            case VIEW_TYPE_NEWS_CARD:
                return ITEM_ID_NEWS_CARD;
            default:
                ReaderPost post = getItem(position);
                return post != null ? post.getStableId() : 0;
        }
    }

    /**
     * Creates 'Removed [post title]' text, with the '[post title]' in bold.
     */
    @NonNull
    private SpannableStringBuilder createTextForRemovedPostContainer(ReaderPost post, Context context) {
        String removedString = context.getString(R.string.removed);
        String removedPostTitle = removedString + " " + post.getTitle();
        SpannableStringBuilder str = new SpannableStringBuilder(removedPostTitle);
        str.setSpan(new StyleSpan(Typeface.BOLD), removedString.length(), removedPostTitle.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return str;
    }

    /*
     * triggered when user taps the like button (textView)
     */
    private void toggleLike(Context context, ReaderPost post) {
        if (post == null || !NetworkUtils.checkConnection(context)) {
            return;
        }

        boolean isCurrentlyLiked = ReaderPostTable.isPostLikedByCurrentUser(post);
        boolean isAskingToLike = !isCurrentlyLiked;

        if (!ReaderPostActions.performLikeAction(post, isAskingToLike, mAccountStore.getAccount().getUserId())) {
            ToastUtils.showToast(context, R.string.reader_toast_err_generic);
            return;
        }

        if (isAskingToLike) {
            AnalyticsUtils.trackWithReaderPostDetails(AnalyticsTracker.Stat.READER_ARTICLE_LIKED, post);
            // Consider a like to be enough to push a page view - solves a long-standing question
            // from folks who ask 'why do I have more likes than page views?'.
            ReaderPostActions.bumpPageViewForPost(mSiteStore, post);
        } else {
            AnalyticsUtils.trackWithReaderPostDetails(AnalyticsTracker.Stat.READER_ARTICLE_UNLIKED, post);
        }

        // update post in array and on screen
        int position = mPosts.indexOfPost(post);
        ReaderPost updatedPost = ReaderPostTable.getBlogPost(post.blogId, post.postId, true);
        if (updatedPost != null && position > -1) {
            mPosts.set(position, updatedPost);
            notifyItemChanged(position);
        }
    }

    /*
     * triggered when user taps the bookmark post button
     */
    private void toggleBookmark(final long blogId, final long postId) {
        ReaderPost post = ReaderPostTable.getBlogPost(blogId, postId, false);

        AnalyticsTracker.Stat eventToTrack;
        if (post.isBookmarked) {
            eventToTrack = isBookmarksList() ? AnalyticsTracker.Stat.READER_POST_UNSAVED_FROM_SAVED_POST_LIST
                    : AnalyticsTracker.Stat.READER_POST_UNSAVED_FROM_OTHER_POST_LIST;
            ReaderPostActions.removeFromBookmarked(post);
        } else {
            eventToTrack = isBookmarksList() ? AnalyticsTracker.Stat.READER_POST_SAVED_FROM_SAVED_POST_LIST
                    : AnalyticsTracker.Stat.READER_POST_SAVED_FROM_OTHER_POST_LIST;
            ReaderPostActions.addToBookmarked(post);
        }

        AnalyticsTracker.track(eventToTrack);

        // update post in array and on screen
        post = ReaderPostTable.getBlogPost(blogId, postId, true);
        int position = mPosts.indexOfPost(post);
        if (post != null && position > -1) {
            mPosts.set(position, post);

            if (mOnPostBookmarkedListener != null) {
                mOnPostBookmarkedListener
                        .onBookmarkedStateChanged(post.isBookmarked, blogId, postId, !isBookmarksList());
            }
        }
    }

    /*
     * triggered when user taps the follow button on a post
     */
    private void toggleFollow(final Context context, final View followButton, final ReaderPost post) {
        if (post == null || !NetworkUtils.checkConnection(context)) {
            return;
        }

        boolean isCurrentlyFollowed = ReaderPostTable.isPostFollowed(post);
        final boolean isAskingToFollow = !isCurrentlyFollowed;

        if (mFollowListener != null) {
            if (isAskingToFollow) {
                mFollowListener.onFollowTapped(followButton, post.getBlogName(), post.blogId);
            } else {
                mFollowListener.onFollowingTapped();
            }
        }

        ReaderActions.ActionListener actionListener = new ReaderActions.ActionListener() {
            @Override
            public void onActionResult(boolean succeeded) {
                followButton.setEnabled(true);
                if (!succeeded) {
                    int resId = (isAskingToFollow ? R.string.reader_toast_err_follow_blog
                            : R.string.reader_toast_err_unfollow_blog);
                    ToastUtils.showToast(context, resId);
                    setFollowStatusForBlog(post.blogId, !isAskingToFollow);
                }
            }
        };

        if (!ReaderBlogActions.followBlogForPost(post, isAskingToFollow, actionListener)) {
            ToastUtils.showToast(context, R.string.reader_toast_err_generic);
            return;
        }

        followButton.setEnabled(false);
        setFollowStatusForBlog(post.blogId, isAskingToFollow);
    }

    public void setFollowStatusForBlog(long blogId, boolean isFollowing) {
        ReaderPost post;
        for (int i = 0; i < mPosts.size(); i++) {
            post = mPosts.get(i);
            if (post.blogId == blogId && post.isFollowedByCurrentUser != isFollowing) {
                post.isFollowedByCurrentUser = isFollowing;
                mPosts.set(i, post);
                notifyItemChanged(i);
            }
        }
    }

    public void removeGapMarker() {
        if (mGapMarkerPosition == -1) {
            return;
        }

        int position = mGapMarkerPosition;
        mGapMarkerPosition = -1;
        if (position < getItemCount()) {
            notifyItemRemoved(position);
        }
    }

    public void updateNewsCardItem(NewsItem newsItem) {
        NewsItem prevState = mNewsItem;
        mNewsItem = newsItem;
        if (prevState == null && newsItem != null) {
            notifyItemInserted(NEWS_CARD_POSITION);
        } else if (prevState != null) {
            if (newsItem == null) {
                notifyItemRemoved(NEWS_CARD_POSITION);
            } else {
                notifyItemChanged(NEWS_CARD_POSITION);
            }
        }
    }

    private boolean hasNewsCard() {
        // We don't want to display the card when we are displaying just a loading screen. However, on Discover a header
        // is shown, even when we are loading data, so the card should be displayed. [moreover displaying the card only
        // after we fetch the data results in weird animation after configuration change, since it plays insertion
        // animation for all the data (including the card) except of the header which hasn't changed].
        return mNewsItem != null && (!isEmpty() || isDiscover());
    }

    /*
     * AsyncTask to load posts in the current tag
     */
    private boolean mIsTaskRunning = false;

    private class LoadPostsTask extends AsyncTask<Void, Void, Boolean> {
        private ReaderPostList mAllPosts;

        private boolean mCanRequestMorePostsTemp;
        private int mGapMarkerPositionTemp;

        @Override
        protected void onPreExecute() {
            mIsTaskRunning = true;
        }

        @Override
        protected void onCancelled() {
            mIsTaskRunning = false;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            int numExisting;
            switch (getPostListType()) {
                case TAG_PREVIEW:
                case TAG_FOLLOWED:
                case SEARCH_RESULTS:
                    mAllPosts = ReaderPostTable.getPostsWithTag(mCurrentTag, MAX_ROWS, EXCLUDE_TEXT_COLUMN);
                    numExisting = ReaderPostTable.getNumPostsWithTag(mCurrentTag);
                    break;
                case BLOG_PREVIEW:
                    if (mCurrentFeedId != 0) {
                        mAllPosts = ReaderPostTable.getPostsInFeed(mCurrentFeedId, MAX_ROWS, EXCLUDE_TEXT_COLUMN);
                        numExisting = ReaderPostTable.getNumPostsInFeed(mCurrentFeedId);
                    } else {
                        mAllPosts = ReaderPostTable.getPostsInBlog(mCurrentBlogId, MAX_ROWS, EXCLUDE_TEXT_COLUMN);
                        numExisting = ReaderPostTable.getNumPostsInBlog(mCurrentBlogId);
                    }
                    break;
                default:
                    return false;
            }

            if (mPosts.isSameListWithBookmark(mAllPosts)) {
                return false;
            }

            // if we're not already displaying the max # posts, enable requesting more when
            // the user scrolls to the end of the list
            mCanRequestMorePostsTemp = (numExisting < ReaderConstants.READER_MAX_POSTS_TO_DISPLAY);

            // determine whether a gap marker exists - only applies to tagged posts
            mGapMarkerPositionTemp = getGapMarkerPosition();

            return true;
        }

        private int getGapMarkerPosition() {
            if (!getPostListType().isTagType()) {
                return -1;
            }

            ReaderBlogIdPostId gapMarkerIds = ReaderPostTable.getGapMarkerIdsForTag(mCurrentTag);
            if (gapMarkerIds == null) {
                return -1;
            }

            int gapMarkerPostPosition = mAllPosts.indexOfIds(gapMarkerIds);
            int gapMarkerPosition = -1;
            if (gapMarkerPostPosition > -1) {
                // remove the gap marker if it's on the last post (edge case but
                // it can happen following a purge)
                if (gapMarkerPostPosition == mAllPosts.size() - 1) {
                    AppLog.w(AppLog.T.READER, "gap marker at/after last post, removed");
                    ReaderPostTable.removeGapMarkerForTag(mCurrentTag);
                } else {
                    // we want the gap marker to appear *below* this post
                    gapMarkerPosition = gapMarkerPostPosition + 1;
                    // increment it if there are custom items at the top of the list (header or newsCard)
                    gapMarkerPosition += getItemPositionOffset();
                    AppLog.d(AppLog.T.READER, "gap marker at position " + gapMarkerPostPosition);
                }
            }
            return gapMarkerPosition;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                ReaderPostAdapter.this.mGapMarkerPosition = mGapMarkerPositionTemp;
                ReaderPostAdapter.this.mCanRequestMorePosts = mCanRequestMorePostsTemp;
                mPosts.clear();
                mPosts.addAll(mAllPosts);
                notifyDataSetChanged();
            }

            if (mDataLoadedListener != null) {
                mDataLoadedListener.onDataLoaded(isEmpty());
            }

            mIsTaskRunning = false;
        }
    }
}
