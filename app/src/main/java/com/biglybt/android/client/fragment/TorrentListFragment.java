/*
 * Copyright (c) Azureus Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package com.biglybt.android.client.fragment;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.*;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.view.ActionMode.Callback;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.widget.Toolbar;
import androidx.collection.LongSparseArray;
import androidx.core.provider.DocumentsContractCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.biglybt.android.adapter.FlexibleRecyclerSelectionListener;
import com.biglybt.android.adapter.SortableRecyclerAdapter;
import com.biglybt.android.client.*;
import com.biglybt.android.client.activity.DrawerActivity;
import com.biglybt.android.client.activity.TorrentViewActivity;
import com.biglybt.android.client.adapter.*;
import com.biglybt.android.client.dialog.DialogFragmentDeleteTorrent;
import com.biglybt.android.client.dialog.DialogFragmentMoveData;
import com.biglybt.android.client.rpc.*;
import com.biglybt.android.client.session.*;
import com.biglybt.android.client.sidelist.*;
import com.biglybt.android.client.spanbubbles.SpanTags;
import com.biglybt.android.core.az.AndroidFileHandler;
import com.biglybt.android.util.*;
import com.biglybt.android.widget.PreCachingLayoutManager;
import com.biglybt.android.widget.SwipeRefreshLayoutExtra;
import com.biglybt.android.widget.SwipeRefreshLayoutExtra.SwipeTextUpdater;
import com.biglybt.util.Thunk;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles a ListView that shows Torrents
 */
public class TorrentListFragment
	extends SideListFragment
	implements TorrentListReceivedListener, SessionListener,
	ActionModeBeingReplacedListener, TagListReceivedListener, View.OnKeyListener,
	SessionSettingsChangedListener, TorrentListRefreshingListener,
	NetworkState.NetworkStateListener
{
	@Thunk
	static final boolean DEBUG = AndroidUtils.DEBUG;

	private static final String TAG = "TorrentList";

	// Shrink sidelist, typically for 7" Tablets in Portrait
	private static final int SIDELIST_COLLAPSE_UNTIL_WIDTH_DP = 500;

	private static final int SIDELIST_COLLAPSE_UNTIL_WIDTH_PX = AndroidUtilsUI.dpToPx(
			SIDELIST_COLLAPSE_UNTIL_WIDTH_DP);

	// Sidelist always full-width, typically for 9"-11" Tablets, 7" Tablets in
	// Landscape, and TVs
	private static final int SIDELIST_KEEP_EXPANDED_AT_DP = 610;

	public interface OnTorrentSelectedListener
		extends ActionModeBeingReplacedListener
	{
		void onTorrentSelectedListener(TorrentListFragment torrentListFragment,
				long[] ids, boolean inMultiMode);
	}

	@Thunk
	public RecyclerView listview;

	@Thunk
	ActionMode mActionMode;

	@Thunk
	public TorrentListAdapter torrentListAdapter;

	private Callback mActionModeCallback;

	@Thunk
	TextView tvFilteringBy;

	@Thunk
	TextView tvTorrentCount;

	@Thunk
	boolean actionModeBeingReplaced;

	private boolean rebuildActionMode;

	@Thunk
	OnTorrentSelectedListener mCallback;

	// >> SideList

	private RecyclerView listSideTags;

	@Thunk
	SideTagAdapter sideTagAdapter;

	private SideActionSelectionListener sideActionSelectionListener;

	// << SideList

	private Boolean isSmall;

	@Thunk
	TextView tvEmpty;

	private ActivityResultLauncher<Intent> permsAuthLauncher;

	// Need to store this in instancestate when dialog is up, cuz it gets restored
	private HashSet<Long> isAskingForPermsFor = null;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		permsAuthLauncher = registerForActivityResult(new StartActivityForResult(),
				this::permsAuthReceived);
		super.onCreate(savedInstanceState);
	}

	@Override
	public void onStart() {
		super.onStart();

		if (torrentListAdapter != null) {
			// Case: 
			// 1) User leaves app, but keeps it running (activity still avail)
			// 2) Loss of Session object (ex. due to limited memory)
			// 3) User returns to app
			// In this case, adapter will still exist, but will have an invalid
			// neverSetItems value. This code will reset it to true if session isn't
			// ready yet.
			torrentListAdapter.setNeverSetItems(!session.isReadyForUI());
		}
	}

	@Override
	public void onAttachWithSession(Context context) {
		super.onAttachWithSession(context);

		if (context instanceof OnTorrentSelectedListener) {
			mCallback = (OnTorrentSelectedListener) context;
		}

		FlexibleRecyclerSelectionListener<TorrentListAdapter, TorrentListHolder, TorrentListAdapterItem> rs = new FlexibleRecyclerSelectionListener<TorrentListAdapter, TorrentListHolder, TorrentListAdapterItem>() {
			@Override
			public void onItemSelected(TorrentListAdapter adapter, final int position,
					boolean isChecked) {
			}

			@Override
			public void onItemClick(TorrentListAdapter adapter, int position) {
			}

			@Override
			public boolean onItemLongClick(TorrentListAdapter adapter, int position) {
				return AndroidUtils.usesNavigationControl()
						&& adapter.getItemViewType(
								position) == TorrentListAdapter.VIEWTYPE_TORRENT
						&& showTorrentContextMenu();
			}

			@Override
			public void onItemCheckedChanged(TorrentListAdapter adapter,
					TorrentListAdapterItem item, boolean isChecked) {
				if (mActionMode == null && isChecked) {
					showContextualActions(false);
				}

				if (adapter.getCheckedItemCount() == 0) {
					finishActionMode();
				}

				if (adapter.isMultiCheckMode()) {
					updateActionModeText(mActionMode);
				}

				if (AndroidUtils.usesNavigationControl() && isChecked) {
					if (item instanceof TorrentListAdapterTorrentItem) {
						TorrentListAdapterTorrentItem torrentItem = (TorrentListAdapterTorrentItem) item;
						Map<String, Object> torrent = torrentItem.getTorrentMap(session);
						boolean needsAuth = MapUtils.getMapBoolean(torrent,
								TransmissionVars.FIELD_TORRENT_NEEDSAUTH, false);
						if (needsAuth) {
							askForPerms(torrentItem.torrentID);
							AndroidUtilsUI.invalidateOptionsMenuHC(getActivity(),
									mActionMode);
							adapter.setItemChecked(item, false);
							return;
						}
					}
				}

				updateCheckedIDs();

				AndroidUtilsUI.invalidateOptionsMenuHC(getActivity(), mActionMode);
			}
		};

		isSmall = session.getRemoteProfile().useSmallLists();
		torrentListAdapter = new TorrentListAdapter(context, this, rs, isSmall,
				this::askForPerms);
		torrentListAdapter.addOnSetItemsCompleteListener(
				adapter -> updateTorrentCount());
		torrentListAdapter.setMultiCheckModeAllowed(
				!AndroidUtils.usesNavigationControl());
	}

	@Override
	public void sessionReadyForUI(TransmissionRPC rpc) {
		OffThread.runOnUIThread(this, false, activity -> uiSessionReadyForUI(rpc));
	}

	@UiThread
	private void uiSessionReadyForUI(TransmissionRPC rpc) {
		RemoteProfile remoteProfile = session.getRemoteProfile();

		setupSideTags(AndroidUtilsUI.requireContentView(requireActivity()));

		long filterBy = remoteProfile.getFilterBy();
		// Convert All Filter to tag if we have tags
		if (filterBy == TorrentListFilter.FILTERBY_ALL
				&& session.getSupports(RPCSupports.SUPPORTS_TAGS)) {
			Long tagAllUID = session.tag.getTagAllUID();
			if (tagAllUID != null) {
				filterBy = tagAllUID;
			}
		}
		if (filterBy > 10) {
			Map<?, ?> tag = session.tag.getTag(filterBy);

			filterBy(filterBy, MapUtils.getMapString(tag, "name", "fooo"), false);
		} else if (filterBy >= 0) {
			final AndroidUtils.ValueStringArray filterByList = AndroidUtils.getValueStringArray(
					getResources(), R.array.filterby_list);
			for (int i = 0; i < filterByList.values.length; i++) {
				long val = filterByList.values[i];
				if (val == filterBy) {
					filterBy(filterBy, filterByList.strings[i], false);
					break;
				}
			}
		}

		SideListActivity sideListActivity = getSideListActivity();
		if (sideListActivity != null) {
			sideListActivity.updateSideActionMenuItems();
		}

		requireActivity().invalidateOptionsMenu();
	}

	@Override
	public View onCreateViewWithSession(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View fragView = inflater.inflate(R.layout.frag_torrent_list, container,
				false);

		setupActionModeCallback();

		final SwipeRefreshLayoutExtra swipeRefresh = fragView.findViewById(
				R.id.swipe_container);
		if (swipeRefresh != null) {
			swipeRefresh.setExtraLayout(R.layout.swipe_layout_extra);

			/* getLastUpdateString uses DateUtils.getRelativeDateTimeString, which
			 * for some reason sometimes takes 4s to initialize.  That's bad when
			 * initializing a view.			
			LastUpdatedInfo lui = getLastUpdatedString();
			if (lui != null) {
				View extraView = swipeRefresh.getExtraView();
				if (extraView != null) {
					TextView tvSwipeText = extraView.findViewById(R.id.swipe_text);
					tvSwipeText.setText(lui.s);
				}
			}
			 */
			swipeRefresh.setOnRefreshListener(
					new SwipeRefreshLayout.OnRefreshListener() {
						@Override
						public void onRefresh() {
							session.torrent.addListReceivedListener(
									new TorrentListReceivedListener() {

										@Override
										public void rpcTorrentListReceived(String callID,
												List<?> addedTorrentMaps, List<String> fields,
												final int[] fileIndexes, List<?> removedTorrentIDs) {
											OffThread.runOnUIThread(TorrentListFragment.this, false,
													activity -> {
														swipeRefresh.setRefreshing(false);
														LastUpdatedInfo lui = getLastUpdatedString();
														View extraView = swipeRefresh.getExtraView();
														if (extraView != null) {
															TextView tvSwipeText = extraView.findViewById(
																	R.id.swipe_text);
															tvSwipeText.setText(lui == null ? "" : lui.s);
														}
													});
											session.torrent.removeListReceivedListener(this);
										}
									}, false);
							session.triggerRefresh(true);
						}
					});
			swipeRefresh.setOnExtraViewVisibilityChange(
					new SwipeTextUpdater(getLifecycle(), (tvSwipeText) -> {
						LastUpdatedInfo lui = getLastUpdatedString();
						if (lui == null) {
							return -1;
						}
						tvSwipeText.setText(lui.s);
						return lui.sinceMS < DateUtils.MINUTE_IN_MILLIS
								? DateUtils.SECOND_IN_MILLIS : DateUtils.MINUTE_IN_MILLIS;
					}));
		}

		torrentListAdapter.setEmptyView(fragView.findViewById(R.id.first_list),
				fragView.findViewById(R.id.empty_list));

		listview = fragView.findViewById(R.id.listTorrents);
		PreCachingLayoutManager layoutManager = new PreCachingLayoutManager(
				getContext());
		listview.setLayoutManager(layoutManager);
		listview.setAdapter(torrentListAdapter);

		if (AndroidUtils.isTV(getContext())) {
			listview.setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_LEFT);
			if (listview instanceof FastScrollRecyclerView) {
				((FastScrollRecyclerView) listview).setFastScrollEnabled(false);
			}
			layoutManager.setFixedVerticalHeight(AndroidUtilsUI.dpToPx(48));
			listview.setVerticalFadingEdgeEnabled(true);
			listview.setFadingEdgeLength(AndroidUtilsUI.dpToPx((int) (48 * 1.5)));
		}

		setHasOptionsMenu(true);

		return fragView;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		if (DEBUG) {
			log(TAG, "onActivityCreated");
		}
		FragmentActivity activity = requireActivity();
		tvFilteringBy = activity.findViewById(R.id.wvFilteringBy);
		tvTorrentCount = activity.findViewById(R.id.wvTorrentCount);
		tvEmpty = activity.findViewById(R.id.tv_empty);
		if (tvEmpty != null) {
			tvEmpty.setText(R.string.torrent_list_empty);
		}

		Toolbar abToolBar = requireActivity().findViewById(R.id.actionbar);
		boolean canShowSideActionsArea = abToolBar == null
				|| abToolBar.getVisibility() == View.GONE;

		sideActionSelectionListener = canShowSideActionsArea ?

				new SideActionSelectionListener() {
					@Override
					public Session getSession() {
						return session;
					}

					@Override
					public void prepareActionMenus(Menu menu) {
						if (session.isDestroyed()) {
							return;
						}
						TorrentViewActivity.prepareGlobalMenu(menu, session);

						int totalUnfiltered = session.torrent.getCount();

						MenuItem[] itemsToShow_min1unfiltered = {
							menu.findItem(R.id.action_start_all),
							menu.findItem(R.id.action_stop_all)
						};

						for (MenuItem menuItem : itemsToShow_min1unfiltered) {
							if (menuItem == null) {
								continue;
							}
							menuItem.setVisible(totalUnfiltered > 1);
						}

						MenuItem itemSearch = menu.findItem(R.id.action_search);
						if (itemSearch != null) {
							itemSearch.setVisible(session.isReadyForUI()
									&& session.getSupports(RPCSupports.SUPPORTS_SEARCH));
						}

						MenuItem itemSwarm = menu.findItem(R.id.action_swarm_discoveries);
						if (itemSwarm != null) {
							itemSwarm.setVisible(session.isReadyForUI()
									&& session.getSupports(RPCSupports.SUPPORTS_RCM));
						}

						MenuItem itemSubs = menu.findItem(R.id.action_subscriptions);
						if (itemSubs != null) {
							itemSubs.setVisible(session.isReadyForUI()
									&& session.getSupports(RPCSupports.SUPPORTS_SUBSCRIPTIONS));
						}
					}

					@Override
					public MenuBuilder getMenuBuilder() {
						Context context = requireContext();
						@SuppressLint("RestrictedApi")
						MenuBuilder menuBuilder = new MenuBuilder(context);
						new MenuInflater(context).inflate(R.menu.menu_torrent_list,
								menuBuilder);
						return menuBuilder;
					}

					@Override
					public int[] getRestrictToMenuIDs() {
						return new int[] {
							R.id.action_refresh,
							R.id.action_add_torrent,
							R.id.action_search,
							R.id.action_swarm_discoveries,
							R.id.action_subscriptions,
							R.id.action_start_all,
							R.id.action_stop_all,
							R.id.action_settings,
							R.id.action_giveback,
							R.id.action_logout,
							R.id.action_shutdown
						};
					}

					@Override
					public boolean isRefreshing() {
						return session.torrent.isRefreshingList();
					}

					@Override
					public void onItemClick(SideActionsAdapter adapter, int position) {
						SideActionsAdapter.SideActionsInfo item = adapter.getItem(position);
						if (item == null) {
							return;
						}
						requireActivity().onOptionsItemSelected(item.menuItem);
					}

					@Override
					public boolean onItemLongClick(SideActionsAdapter adapter,
							int position) {
						return false;
					}

					@Override
					public void onItemSelected(SideActionsAdapter adapter, int position,
							boolean isChecked) {

					}

					@Override
					public void onItemCheckedChanged(SideActionsAdapter adapter,
							SideActionsAdapter.SideActionsInfo item, boolean isChecked) {

					}
				} : null;

		super.onActivityCreated(savedInstanceState);
	}

	@Override
	public SortableRecyclerAdapter getMainAdapter() {
		return torrentListAdapter;
	}

	@Override
	public SideActionSelectionListener getSideActionSelectionListener() {
		return sideActionSelectionListener;
	}

	@Override
	public void sideListExpandListChanging(boolean expanded) {
		super.sideListExpandListChanging(expanded);
		if (expanded) {
			if (sideTagAdapter != null) {
				sideTagAdapter.notifyDataSetChanged();
			}
		}
	}

	@Override
	public void onSideListHelperVisibleSetup(View view) {
		super.onSideListHelperVisibleSetup(view);

		if (session != null && session.isDestroyed()) {
			return;
		}
		setupSideTags(view);
	}

	@Override
	public void onSideListHelperCreated(SideListHelper sideListHelper) {
		super.onSideListHelperCreated(sideListHelper);
		FragmentActivity activity = getActivity();
		if (activity == null) {
			return;
		}
		View view = AndroidUtilsUI.requireContentView(activity);

		Toolbar abToolBar = activity.findViewById(R.id.actionbar);

		boolean setupForDrawer = abToolBar != null
				&& (activity instanceof DrawerActivity)
				&& ((DrawerActivity) activity).getDrawerLayout() != null;
		sideListHelper.setDimensionLimits(
				setupForDrawer ? 0 : SIDELIST_COLLAPSE_UNTIL_WIDTH_PX,
				setupForDrawer ? 0 : SIDELIST_KEEP_EXPANDED_AT_DP);

		sideListHelper.addEntry("tag", view, R.id.sidetag_header,
				R.id.sidetag_list);
	}

	@Override
	public boolean showFilterEntry() {
		return true;
	}

	private void setupSideTags(View view) {
		if (!session.isReadyForUI()) {
			if (DEBUG) {
				log(TAG, "Skip setupSideTags, session not ready. "
						+ AndroidUtils.getCompressedStackTrace());
			}
			return;
		}
		RecyclerView newListSideTags = view.findViewById(R.id.sidetag_list);
		if (newListSideTags != listSideTags) {
			listSideTags = newListSideTags;
			if (listSideTags == null) {
				return;
			}

			listSideTags.setLayoutManager(new PreCachingLayoutManager(getContext()));
		}

		if (sideTagAdapter == null) {
			sideTagAdapter = new SideTagAdapter(getRemoteProfileID(),
					new FlexibleRecyclerSelectionListener<SideTagAdapter, SideTagAdapter.SideTagHolder, SideTagAdapter.SideTagInfo>() {
						@Override
						public void onItemClick(SideTagAdapter adapter, int position) {
						}

						@Override
						public boolean onItemLongClick(SideTagAdapter adapter,
								int position) {
							return false;
						}

						@Override
						public void onItemSelected(SideTagAdapter adapter, int position,
								boolean isChecked) {
						}

						@Override
						public void onItemCheckedChanged(SideTagAdapter adapter,
								SideTagAdapter.SideTagInfo item, boolean isChecked) {

							if (!isChecked) {
								return;
							}
							adapter.setItemChecked(item, false);

							filterBy(item.id, MapUtils.getMapString(
									session.tag.getTag(item.id), "name", ""), true);
						}
					});
		} else {
			sideTagAdapter.removeAllItems();
		}
		listSideTags.setAdapter(sideTagAdapter);

		if (!session.getSupports(RPCSupports.SUPPORTS_TAGS)) {
			// TRANSMISSION
			AndroidUtils.ValueStringArray filterByList = AndroidUtils.getValueStringArray(
					getResources(), R.array.filterby_list);

			int num = filterByList.strings.length;
			if (num > 0) {
				SideTagAdapter.SideTagInfo[] tagsToAdd = new SideTagAdapter.SideTagInfo[num];
				for (int i = 0; i < num; i++) {
					long id = filterByList.values[i];
					Map<String, Object> map = new ConcurrentHashMap<>(1);
					map.put("uid", id);
					tagsToAdd[i] = new SideTagAdapter.SideTagInfoItem(map);
				}
				sideTagAdapter.addItem(tagsToAdd);
			}
		} else {
			List<Map<?, ?>> tags = session.tag.getTags();
			if (tags.size() > 0) {
				tagListReceived(tags);
			}
		}
	}

	@Override
	public boolean onKey(View v, int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_UP) {
			return false;
		}
		switch (keyCode) {
			// NOTE:
			// KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_MENU);
			case KeyEvent.KEYCODE_MENU:
			case KeyEvent.KEYCODE_BUTTON_X:
			case KeyEvent.KEYCODE_INFO: {
				return showTorrentContextMenu();
			}

			case KeyEvent.KEYCODE_PROG_YELLOW:
			case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: {
				SideListActivity sideListActivity = getSideListActivity();
				if (sideListActivity != null) {
					sideListActivity.flipSideListExpandState();
				}
				return true;
			}
		}
		return false;
	}

	@Thunk
	boolean showTorrentContextMenu() {
		int selectedPosition = torrentListAdapter.getSelectedPosition();
		if (selectedPosition < 0) {
			return false;
		}
		String s;
		int checkedItemCount = torrentListAdapter.getCheckedItemCount();
		if (checkedItemCount <= 1) {
			Map<?, ?> item = torrentListAdapter.getTorrentItem(selectedPosition);
			s = getResources().getString(R.string.torrent_actions_for,
					MapUtils.getMapString(item, "name", "???"));
		} else {
			s = getResources().getQuantityString(
					R.plurals.torrent_actions_for_multiple, checkedItemCount,
					checkedItemCount);
		}

		return AndroidUtilsUI.popupContextMenu(getContext(), mActionModeCallback,
				s);
	}

	@Override
	public void sessionSettingsChanged(SessionSettings newSessionSettings) {
		boolean isSmallNew = session.getRemoteProfile().useSmallLists();
		if (isSmall != null && isSmallNew != isSmall) {
			// getActivity().recreate() will recreate the closing session config window
			FragmentActivity activity = requireActivity();
			Intent intent = activity.getIntent();
			activity.finish();
			startActivity(intent);
		}
		isSmall = isSmallNew;

		SideListActivity sideListActivity = getSideListActivity();
		if (sideListActivity != null) {
			sideListActivity.updateSideActionMenuItems();
		}
	}

	@Override
	public void speedChanged(long downloadSpeed, long uploadSpeed) {

	}

	@Override
	public void rpcTorrentListRefreshingChanged(final boolean refreshing) {
		OffThread.runOnUIThread(this, false, ctivity -> {
			SideListActivity sideListActivity = getSideListActivity();
			if (sideListActivity != null) {
				sideListActivity.updateSideListRefreshButton();
			}
		});
	}

	@Override
	public void onlineStateChanged(boolean isOnline, boolean isOnlineMobile) {
		OffThread.runOnUIThread(this, false, activity -> {
			SideListActivity sideListActivity = getSideListActivity();
			if (sideListActivity != null) {
				sideListActivity.updateSideActionMenuItems();
			}
		});
	}

	private static class LastUpdatedInfo
	{
		final long sinceMS;

		final String s;

		LastUpdatedInfo(long sinceMS, String s) {
			this.sinceMS = sinceMS;
			this.s = s;
		}

	}

	@Nullable
	@Thunk
	LastUpdatedInfo getLastUpdatedString() {
		FragmentActivity activity = getActivity();
		if (activity == null) {
			return null;
		}
		long lastUpdated = session.torrent.getLastListReceivedOn();
		if (lastUpdated == 0) {
			return new LastUpdatedInfo(0, "");
		}
		long sinceMS = System.currentTimeMillis() - lastUpdated;
		String since = DateUtils.getRelativeDateTimeString(activity, lastUpdated,
				DateUtils.SECOND_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString();
		String s = activity.getResources().getString(R.string.last_updated, since);

		return new LastUpdatedInfo(sinceMS, s);
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		if (DEBUG) {
			log(TAG, "onSaveInstanceState " + isAdded());
		}
		if (torrentListAdapter != null) {
			torrentListAdapter.onSaveInstanceState(outState);
		}
		if (isAskingForPermsFor != null) {
			outState.putSerializable("isAskingForPermsFor", isAskingForPermsFor);
		}
		super.onSaveInstanceState(outState);
	}

	@Override
	public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
		if (DEBUG) {
			log(TAG, "onViewStateRestored");
		}
		super.onViewStateRestored(savedInstanceState);
		if (savedInstanceState != null) {
			try {
				isAskingForPermsFor = (HashSet<Long>) savedInstanceState.getSerializable(
						"isAskingForPermsFor");
			} catch (Throwable t) {
				if (DEBUG) {
					Log.e(TAG, "onViewStateRestored: ", t);
				}
			}

			if (torrentListAdapter != null) {
				torrentListAdapter.onRestoreInstanceState(savedInstanceState);
			}
		}
		if (listview != null) {
			updateCheckedIDs();
		}
	}

	@Override
	public void onShowFragment() {
		super.onShowFragment();

		BiglyBTApp.getNetworkState().addListener(this);

		session.torrent.addListReceivedListener(TAG, this);
		session.tag.addTagListReceivedListener(this);
		session.addSessionListener(this);
		session.addSessionSettingsChangedListeners(this);
		session.torrent.addTorrentListRefreshingListener(this, false);
	}

	@Override
	public void onHideFragment() {
		super.onHideFragment();

		BiglyBTApp.getNetworkState().removeListener(this);

		session.tag.removeTagListReceivedListener(this);
		session.torrent.removeListReceivedListener(this);
		session.torrent.removeListRefreshingListener(this);
		session.removeSessionSettingsChangedListeners(this);
	}

	@Thunk
	void finishActionMode() {
		if (mActionMode != null) {
			mActionMode.finish();
			mActionMode = null;
		}
		torrentListAdapter.clearChecked();
	}

	@NonNull
	private static Map<?, ?>[] getCheckedTorrentMaps(TorrentListAdapter adapter) {
		if (adapter == null) {
			return new Map[0];
		}
		int[] checkedItems = adapter.getCheckedItemPositions();
		if (checkedItems.length == 0) {
			int selectedPosition = adapter.getSelectedPosition();
			if (selectedPosition < 0) {
				return new Map[0];
			}
			checkedItems = new int[] {
				selectedPosition
			};
		}

		List<Map> list = new ArrayList<>(checkedItems.length);

		for (int position : checkedItems) {
			Map<?, ?> torrent = adapter.getTorrentItem(position);
			if (torrent != null) {
				list.add(torrent);
			}
		}

		return list.toArray(new Map[0]);
	}

	@Thunk
	static long[] getCheckedIDs(TorrentListAdapter adapter,
			boolean includeSelected) {

		List<Long> list = getCheckedIDsList(adapter, includeSelected);

		long[] longs = new long[list.size()];
		for (int i = 0; i < list.size(); i++) {
			longs[i] = list.get(i);
		}

		return longs;
	}

	private static List<Long> getCheckedIDsList(TorrentListAdapter adapter,
			boolean includeSelected) {
		List<Long> list = new ArrayList<>();
		if (adapter == null) {
			return list;
		}
		int[] checkedItems = adapter.getCheckedItemPositions();

		if (checkedItems.length == 0) {
			if (!includeSelected) {
				return list;
			}
			int selectedPosition = adapter.getSelectedPosition();
			if (selectedPosition < 0) {
				return list;
			}
			long torrentID = adapter.getTorrentID(selectedPosition);
			if (torrentID >= 0) {
				list.add(torrentID);
			}
			return list;
		} else {
			for (int position : checkedItems) {
				long torrentID = adapter.getTorrentID(position);
				if (torrentID >= 0) {
					list.add(torrentID);
				}
			}
		}

		return list;
	}

	@Override
	public void rpcTorrentListReceived(String callID, List<?> addedTorrentMaps,
			List<String> fields, final int[] fileIndexes, List<?> removedTorrentIDs) {
		if ((addedTorrentMaps == null || addedTorrentMaps.size() == 0)
				&& (removedTorrentIDs == null || removedTorrentIDs.size() == 0)) {
			if (torrentListAdapter.isNeverSetItems()) {
				torrentListAdapter.triggerEmptyList();
			}
			return;
		}
		OffThread.runOnUIThread(this, false, activity -> {
			rebuildActionMode();
			if (torrentListAdapter == null) {
				return;
			}
			torrentListAdapter.refreshDisplayList();
		});
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		if (AndroidUtils.DEBUG_MENU) {
			log(TAG, "onOptionsItemSelected " + item.getTitle());
		}

		return super.onOptionsItemSelected(item) || handleFragmentMenuItems(item);
	}

	@Thunk
	boolean handleFragmentMenuItems(MenuItem menuItem) {
		if (AndroidUtils.DEBUG_MENU) {
			log(TAG, "HANDLE MENU FRAG " + menuItem.getItemId());
		}
		return handleTorrentMenuActions(session,
				getCheckedIDs(torrentListAdapter, true),
				AndroidUtilsUI.getSafeParentFragmentManager(this), menuItem);
	}

	public static boolean handleTorrentMenuActions(Session session,
			final long[] ids, FragmentManager fm, MenuItem menuItem) {
		if (AndroidUtils.DEBUG_MENU) {
			Log.d(TAG, "HANDLE TORRENTMENU FRAG " + menuItem.getItemId());
		}
		if (ids == null || ids.length == 0) {
			return false;
		}

		int itemId = menuItem.getItemId();
		if (itemId == R.id.action_sel_remove) {
			for (final long torrentID : ids) {
				Map<?, ?> map = session.torrent.getCachedTorrent(torrentID);
				long id = MapUtils.getMapLong(map, "id", -1);
				boolean isMagnetTorrent = TorrentUtils.isMagnetTorrent(
						session.torrent.getCachedTorrent(id));
				if (!isMagnetTorrent) {
					String name = MapUtils.getMapString(map, "name", "");
					// TODO: One at a time!
					DialogFragmentDeleteTorrent.open(fm, session, name, id);
				} else {
					session.torrent.removeTorrent(new long[] {
						id
					}, true, (SuccessReplyMapRecievedListener) (id1, optionalMap) -> {
						// removeTorrent will call getRecentTorrents, but alas,
						// magnet torrent removal isn't listed there (bug in xmwebui)
						session.torrent.clearTorrentFromCache(torrentID);
					});
				}
			}
			return true;
		}

		if (itemId == R.id.action_sel_start) {
			session.torrent.startTorrents(ids, false);
			return true;
		}

		if (itemId == R.id.action_sel_forcestart) {
			boolean force = true;
			if (menuItem.isCheckable()) {
				force = !menuItem.isChecked();
				menuItem.setChecked(force);
			}
			session.torrent.startTorrents(ids, force);
			return true;
		}

		if (itemId == R.id.action_sel_sequential) {
			boolean sequential = !menuItem.isChecked();
			menuItem.setChecked(sequential);
			session.torrent.setSequential(TAG, ids, sequential);
			return true;
		}

		if (itemId == R.id.action_sel_stop) {
			session.torrent.stopTorrents(ids);
			return true;
		}

		if (itemId == R.id.action_sel_verify) {
			session.torrent.verifyTorrents(ids);
			return true;
		}

		if (itemId == R.id.action_sel_relocate) {// TODO: Handle multiple
			DialogFragmentMoveData.openMoveDataDialog(ids[0], session, fm);
			return true;
		}

		if (itemId == R.id.action_sel_move_top) {
			session.executeRpc(rpc -> rpc.simpleRpcCall(
					TransmissionVars.METHOD_Q_MOVE_TOP, ids, null));
			return true;
		}

		if (itemId == R.id.action_sel_move_up) {
			session.executeRpc(rpc -> rpc.simpleRpcCall("queue-move-up", ids, null));
			return true;
		}

		if (itemId == R.id.action_sel_move_down) {
			session.executeRpc(
					rpc -> rpc.simpleRpcCall("queue-move-down", ids, null));
			return true;
		}

		if (itemId == R.id.action_sel_move_bottom) {
			session.executeRpc(rpc -> rpc.simpleRpcCall(
					TransmissionVars.METHOD_Q_MOVE_BOTTOM, ids, null));
			return true;
		}

		return false;
	}

	@Thunk
	void updateActionModeText(ActionMode mode) {
		if (AndroidUtils.DEBUG_MENU) {
			log(TAG, "MULTI:CHECK CHANGE");
		}

		if (mode != null && isAdded()) {
			String subtitle = getString(R.string.context_torrent_subtitle_selected,
					torrentListAdapter.getCheckedItemCount());
			mode.setSubtitle(subtitle);
		}
	}

	private void setupActionModeCallback() {
		mActionModeCallback = new Callback() {

			// Called when the action mode is created; startActionMode() was called
			@Override
			public boolean onCreateActionMode(ActionMode mode, Menu menu) {

				if (AndroidUtils.DEBUG_MENU) {
					log(TAG, "onCreateActionMode");
				}

				if (mode == null && torrentListAdapter.getCheckedItemCount() == 0
						&& torrentListAdapter.getSelectedPosition() < 0) {
					return false;
				}

				if (mode != null) {
					mode.setTitle(R.string.context_torrent_title);
				}
				FragmentActivity activity = requireActivity();
				activity.getMenuInflater().inflate(R.menu.menu_context_torrent_details,
						menu);

				TorrentDetailsFragment frag = (TorrentDetailsFragment) activity.getSupportFragmentManager().findFragmentById(
						R.id.frag_torrent_details);
				if (frag != null) {
					frag.onCreateActionMode(mode, menu);
				}

				SubMenu subMenu = menu.addSubMenu(R.string.menu_global_actions);
				subMenu.setIcon(R.drawable.ic_menu_white_24dp);
				subMenu.getItem().setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

				try {
					// Place "Global" actions on top bar in collapsed menu
					MenuInflater mi = mode == null ? activity.getMenuInflater()
							: mode.getMenuInflater();
					mi.inflate(R.menu.menu_torrent_list, subMenu);
					onPrepareOptionsMenu(subMenu);
				} catch (UnsupportedOperationException e) {
					Log.e(TAG, e.getMessage());
					menu.removeItem(subMenu.getItem().getItemId());
				}

				if (AndroidUtils.usesNavigationControl()) {
					MenuItem add = menu.add(R.string.select_multiple_items);
					add.setCheckable(true);
					add.setChecked(torrentListAdapter.isMultiCheckMode());
					add.setOnMenuItemClickListener(item -> {
						boolean turnOn = !torrentListAdapter.isMultiCheckModeAllowed();

						torrentListAdapter.setMultiCheckModeAllowed(turnOn);
						if (turnOn) {
							torrentListAdapter.setMultiCheckMode(true);
							torrentListAdapter.setItemChecked(
									torrentListAdapter.getSelectedPosition(), true);
						}
						return true;
					});
				}

				if (mode != null) {
					int styleColor = AndroidUtilsUI.getStyleColor(requireContext(),
							android.R.attr.textColorPrimary);
					AndroidUtilsUI.tintAllIcons(menu, styleColor);
				}

				return true;
			}

			// Called each time the action mode is shown. Always called after
			// onCreateActionMode, but
			// may be called multiple times if the mode is invalidated.
			@Override
			public boolean onPrepareActionMode(ActionMode mode, Menu menu) {

				if (AndroidUtils.DEBUG_MENU) {
					log(TAG, "MULTI:onPrepareActionMode " + mode);
				}

				// Must be called first, because our drawer sets all menu items
				// visible.. :(
				FragmentActivity activity = requireActivity();
				activity.onPrepareOptionsMenu(menu);

				prepareContextMenu(menu);

				TorrentDetailsFragment frag = (TorrentDetailsFragment) activity.getSupportFragmentManager().findFragmentById(
						R.id.frag_torrent_details);
				if (frag != null) {
					frag.onPrepareActionMode(menu);
				}

				AndroidUtils.fixupMenuAlpha(menu);

				return true;
			}

			// Called when the user selects a contextual menu item
			@Override
			public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
				if (AndroidUtils.DEBUG_MENU) {
					log(TAG, "onActionItemClicked " + item.getTitle());
				}

				FragmentActivity activity = requireActivity();
				if (activity.onOptionsItemSelected(item)) {
					return true;
				}
				TorrentDetailsFragment frag = (TorrentDetailsFragment) activity.getSupportFragmentManager().findFragmentById(
						R.id.frag_torrent_details);
				if (frag != null && frag.onActionItemClicked(item)) {
					return true;
				}
				if (TorrentListFragment.this.handleFragmentMenuItems(item)) {
					return true;
				}

				return false;
			}

			// Called when the user exits the action mode
			@Override
			public void onDestroyActionMode(ActionMode mode) {
				if (AndroidUtils.DEBUG_MENU) {
					log(TAG,
							"onDestroyActionMode. BeingReplaced?" + actionModeBeingReplaced);
				}

				mActionMode = null;

				if (!actionModeBeingReplaced) {
					listview.post(() -> {
						torrentListAdapter.setMultiCheckMode(false);
						torrentListAdapter.clearChecked();
						updateCheckedIDs();
					});

					listview.post(() -> {
						if (mCallback != null) {
							mCallback.actionModeBeingReplacedDone();
						}
					});

					listview.setLongClickable(true);
					listview.requestLayout();
					AndroidUtilsUI.invalidateOptionsMenuHC(getActivity(), mActionMode);
				}
			}
		};
	}

	@Thunk
	void prepareContextMenu(Menu menu) {
		prepareTorrentMenuItems(menu, getCheckedTorrentMaps(torrentListAdapter),
				getSession());
	}

	public static void prepareTorrentMenuItems(Menu menu, Map[] torrents,
			Session session) {
		boolean isLocalHost = session.getRemoteProfile().isLocalHost();
		boolean isOnlineOrLocal = BiglyBTApp.getNetworkState().isOnline()
				|| isLocalHost;

		int numTorrents = session.torrent.getCount();

		MenuItem menuMove = menu.findItem(R.id.action_sel_move);
		if (menuMove != null) {
			boolean enabled = isOnlineOrLocal && torrents.length > 0
					&& numTorrents > 1;
			menuMove.setEnabled(enabled);
		}

		boolean canStart = false;
		boolean canStop = false;
		boolean isMagnet;
		boolean allForceStart = false;
		boolean allSequential = false;
		if (isOnlineOrLocal) {
			boolean allMagnets = allForceStart = allSequential = torrents.length > 0;
			for (Map<?, ?> mapTorrent : torrents) {
				isMagnet = TorrentUtils.isMagnetTorrent(mapTorrent);
				if (!isMagnet) {
					allMagnets = false;
					canStart |= TorrentUtils.canStart(mapTorrent, session);
					canStop |= TorrentUtils.canStop(mapTorrent, session);
				}
				allForceStart &= MapUtils.getMapBoolean(mapTorrent,
						TransmissionVars.FIELD_TORRENT_IS_FORCED, false);
				allSequential &= MapUtils.getMapBoolean(mapTorrent,
						TransmissionVars.FIELD_TORRENT_SEQUENTIAL, false);
			}

			if (allMagnets) {
				AndroidUtilsUI.setManyMenuItemsVisible(false, menu,
						R.id.action_sel_forcestart, R.id.action_sel_sequential,
						R.id.action_sel_move, R.id.action_sel_relocate);
			}
		}
		if (AndroidUtils.DEBUG_MENU) {
			Log.d(TAG, "prepareContextMenu: " + canStart + "/" + canStop + " via "
					+ AndroidUtils.getCompressedStackTrace());
		}

		MenuItem menuStart = menu.findItem(R.id.action_sel_start);
		if (menuStart != null) {
			menuStart.setVisible(canStart);
		}

		MenuItem menuStop = menu.findItem(R.id.action_sel_stop);
		if (menuStop != null) {
			menuStop.setVisible(canStop);
		}

		MenuItem menuForceStart = menu.findItem(R.id.action_sel_forcestart);
		if (menuForceStart != null) {
			if (session.getSupports(RPCSupports.SUPPORTS_FIELD_ISFORCED)) {
				menuForceStart.setCheckable(true);
				menuForceStart.setChecked(allForceStart);
			} else {
				menuForceStart.setCheckable(false);
			}
		}

		MenuItem menuSequential = menu.findItem(R.id.action_sel_sequential);
		if (menuSequential != null) {
			if (session.getSupports(RPCSupports.SUPPORTS_FIELD_SEQUENTIAL)) {
				menuSequential.setVisible(true);
				menuSequential.setChecked(allSequential);
			} else {
				menuSequential.setVisible(false);
			}
		}

		AndroidUtilsUI.setManyMenuItemsEnabled(isOnlineOrLocal, menu,
				R.id.action_sel_remove, R.id.action_sel_forcestart,
				R.id.action_sel_sequential, R.id.action_sel_move,
				R.id.action_sel_relocate);
	}

	@Thunk
	boolean showContextualActions(boolean forceRebuild) {
		if (AndroidUtils.isTV(getContext())) {
			// TV doesn't get action bar changes, because it's impossible to get to
			// with client control when you are on row 4000
			return false;
		}

		int checkedItemCount = torrentListAdapter != null
				? torrentListAdapter.getCheckedItemCount() : 0;
		boolean needsActionMode = checkedItemCount > 0;
		boolean hasActionMode = mActionMode != null;
		if (hasActionMode == needsActionMode) {
			if (hasActionMode) {
				if (AndroidUtils.DEBUG_MENU) {
					log(TAG, "showContextualActions: invalidate existing");
				}
				mActionMode.invalidate();
			}
			return false;
		}

		if (hasActionMode) {
			if (AndroidUtils.DEBUG_MENU) {
				log(TAG, "showContextualActions: destroy actionmode");
			}
			mActionMode.finish();
			mActionMode = null;
			return false;
		}

		if (mCallback != null) {
			mCallback.setActionModeBeingReplaced(mActionMode, true);
		}
		// Start the CAB using the ActionMode.Callback defined above
		FragmentActivity activity = getActivity();
		if (activity instanceof AppCompatActivity) {
			AppCompatActivity abActivity = (AppCompatActivity) activity;
			ActionBar ab = abActivity.getSupportActionBar();

			if (AndroidUtils.DEBUG_MENU) {
				log(TAG, "showContextualActions: startAB. mActionMode = " + mActionMode
						+ "; isShowing=" + (ab == null ? null : ab.isShowing()));
			}

			actionModeBeingReplaced = true;

			mActionMode = abActivity.startSupportActionMode(mActionModeCallback);
			actionModeBeingReplaced = false;
			if (mActionMode != null) {
				mActionMode.setSubtitle(R.string.multi_select_tip);
				mActionMode.setTitle(R.string.context_torrent_title);
			}
		}
		if (mCallback != null) {
			mCallback.setActionModeBeingReplaced(mActionMode, false);
		}

		return true;
	}

	@Thunk
	void filterBy(final long filterMode, final String name, boolean save) {
		if (DEBUG) {
			log(TAG, "FILTER BY " + name);
		}

		OffThread.runOnUIThread(this, false, activity -> {
			if (torrentListAdapter == null) {
				if (DEBUG) {
					log(TAG, "No torrentListAdapter in filterBy");
				}
				return;
			}
			// java.lang.RuntimeException: Can't create handler inside thread that
			// has not called Looper.prepare()
			TorrentListFilter filter = torrentListAdapter.getTorrentFilter();
			filter.setFilterMode(filterMode);
			if (tvFilteringBy != null) {
				Session session = getSession();
				Map<?, ?> tag = session.tag.getTag(filterMode);
				SpanTags spanTags = new SpanTags(tvFilteringBy, null);
				spanTags.setCountFontRatio(0.8f);
				if (tag == null) {
					spanTags.addTagNames(Collections.singletonList(name));
				} else {
					ArrayList<Map<?, ?>> arrayList = new ArrayList<>(1);
					arrayList.add(tag);
					spanTags.setTagMaps(arrayList);
				}
				spanTags.setShowIcon(false);
				spanTags.updateTags();
			} else {
				if (DEBUG) {
					log(TAG, "null field in filterBy");
				}
			}
		});
		if (save) {
			Session session = getSession();
			session.getRemoteProfile().setFilterBy(filterMode);
			session.saveProfile();
		}
	}

	@Thunk
	void updateTorrentCount() {
		OffThread.runOnUIThread(this, false, activity -> {
			String s = "";
			int total = torrentListAdapter.getItemCount(
					TorrentListAdapter.VIEWTYPE_TORRENT);

			SideListActivity sideListActivity = getSideListActivity();
			if (sideListActivity != null) {
				sideListActivity.updateSideActionMenuItems();
			}

			CharSequence constraint = torrentListAdapter.getFilter().getConstraint();
			boolean constraintEmpty = constraint == null || constraint.length() == 0;

			if (total != 0) {
				if (!constraintEmpty) {
					s = getResources().getQuantityString(R.plurals.torrent_count, total,
							total);
				}
			} else {

				if (tvEmpty != null) {
					Session session = getSession();
					int size = session.torrent.getCount();
					tvEmpty.setText(size > 0 ? R.string.list_filtered_empty
							: R.string.torrent_list_empty);
				}

			}
			if (tvTorrentCount != null) {
				tvTorrentCount.setText(s);
			}
		});
	}

	@Override
	public void setActionModeBeingReplaced(ActionMode actionMode,
			boolean actionModeBeingReplaced) {
		if (AndroidUtils.DEBUG_MENU) {
			log(TAG,
					"setActionModeBeingReplaced: replaced? " + actionModeBeingReplaced
							+ "; hasActionMode? " + (mActionMode != null));
		}
		this.actionModeBeingReplaced = actionModeBeingReplaced;
		if (actionModeBeingReplaced) {
			rebuildActionMode = mActionMode != null;
			if (rebuildActionMode) {
				mActionMode.finish();
				mActionMode = null;
			}
		}
	}

	@Override
	public void actionModeBeingReplacedDone() {
		if (AndroidUtils.DEBUG_MENU) {
			log(TAG, "actionModeBeingReplacedDone: rebuild? " + rebuildActionMode);
		}
		if (rebuildActionMode) {
			rebuildActionMode = false;

			rebuildActionMode();
			torrentListAdapter.setMultiCheckMode(false);
		}
	}

	@Override
	public ActionMode getActionMode() {
		return mActionMode;
	}

	public void clearSelection() {
		finishActionMode();
	}

	@Thunk
	void updateCheckedIDs() {
		List<Long> checkedTorrentIDs = getCheckedIDsList(torrentListAdapter, false);
		if (mCallback != null) {

			long[] longs = new long[checkedTorrentIDs.size()];
			for (int i = 0; i < checkedTorrentIDs.size(); i++) {
				longs[i] = checkedTorrentIDs.get(i);
			}

			mCallback.onTorrentSelectedListener(TorrentListFragment.this, longs,
					torrentListAdapter.isMultiCheckMode());
		}
		if (checkedTorrentIDs.size() == 0 && mActionMode != null) {
			mActionMode.finish();
			mActionMode = null;
		}
	}

	@Override
	public void rebuildActionMode() {
		showContextualActions(true);
	}

	public void startStopTorrents() {
		Map<?, ?>[] checkedTorrentMaps = getCheckedTorrentMaps(torrentListAdapter);
		if (checkedTorrentMaps.length == 0) {
			return;
		}
		//boolean canStart = false;
		boolean canStop = false;
		for (Map<?, ?> mapTorrent : checkedTorrentMaps) {
			int status = MapUtils.getMapInt(mapTorrent,
					TransmissionVars.FIELD_TORRENT_STATUS,
					TransmissionVars.TR_STATUS_STOPPED);
			//canStart |= status == TransmissionVars.TR_STATUS_STOPPED;
			canStop |= status != TransmissionVars.TR_STATUS_STOPPED;
		}

		Session session = getSession();
		if (!canStop) {
			long[] ids = getCheckedIDs(torrentListAdapter, true);
			session.torrent.stopTorrents(ids);
		} else {
			long[] ids = getCheckedIDs(torrentListAdapter, true);
			session.torrent.startTorrents(ids, false);
		}
	}

	@Override
	public void tagListReceived(@NonNull List<Map<?, ?>> changedTags) {
		if (sideTagAdapter == null) {
			return;
		}

		final List<Map<?, ?>> tags = session.tag.getTags();

		List<SideTagAdapter.SideTagInfo> list = new ArrayList<>(tags.size());
		String lastGroup = null;
		for (Map tag : tags) {
			int tagType = MapUtils.getMapInt(tag, TransmissionVars.FIELD_TAG_TYPE, 0);

			if (tagType == 1 && !session.tag.hasCategories()
					&& !Objects.equals(
							MapUtils.getMapLong(tag, TransmissionVars.FIELD_TAG_UID, -1),
							session.tag.getTagAllUID())) {
				// category, no categories, not "All", must be "Uncat".. hide
				continue;
			}

			if (tagType == 3) {
				String group = MapUtils.getMapString(tag,
						TransmissionVars.FIELD_TAG_GROUP, null);
				if (!Objects.equals(lastGroup, group)) {
					lastGroup = group;

					if (lastGroup != null) {
						list.add(new SideTagAdapter.SideTagInfoHeader(lastGroup));
					} else if (AndroidUtils.DEBUG) {
						log(TAG, "switched to null group for " + tag);
					}
				}
			}
			if (MapUtils.getMapLong(tag, TransmissionVars.FIELD_TAG_COUNT, 0) > 0) {
				list.add(new SideTagAdapter.SideTagInfoItem(tag));
			}
		}
		sideTagAdapter.setItems(list, null, (oldItem, newItem) -> {

			if (oldItem.id != newItem.id || session == null
					|| session.isDestroyed()) {
				return false;
			}

			if (newItem instanceof SideTagAdapter.SideTagInfoHeader) {
				return true;
			}

			return !changedTags.contains(session.tag.getTag(newItem.id));
		});

		OffThread.runOnUIThread(this, false, activity -> {
			if (tvFilteringBy == null) {
				return;
			}
			tvFilteringBy.invalidate();
		});
	}

	@Thunk
	void askForPerms(long torrentId) {
		Context context = requireContext();

		Map<?, ?> torrentItem = session.torrent.getCachedTorrent(torrentId);
		String dlDir = MapUtils.getMapString(torrentItem,
				TransmissionVars.FIELD_TORRENT_DOWNLOAD_DIR, null);

		boolean isContentUri = FileUtils.isContentPath(dlDir);

		if (!isContentUri) {
			if (!TorrentUtils.isSimpleTorrent(torrentItem)) {
				dlDir = new File(dlDir).getParent();
			}
		}

		String names = "";
		Uri uri = FileUtils.guessTreeUri(context, dlDir, false);
		if (uri == null) {
			torrentItem.remove(TransmissionVars.FIELD_TORRENT_NEEDSAUTH);
			return;
		}

		isAskingForPermsFor = new HashSet<>();
		isAskingForPermsFor.add(torrentId);

		LongSparseArray<Map<?, ?>> torrentList = session.torrent.getListAsSparseArray();
		int size = torrentList.size();
		for (int i = 0; i < size; i++) {
			Map<?, ?> item = torrentList.get(torrentList.keyAt(i));

			if (!MapUtils.getMapBoolean(item,
					TransmissionVars.FIELD_TORRENT_NEEDSAUTH, false)) {
				continue;
			}

			String walkdlDir = MapUtils.getMapString(item,
					TransmissionVars.FIELD_TORRENT_DOWNLOAD_DIR, null);

			if (isContentUri != FileUtils.isContentPath(walkdlDir)) {
				continue;
			}

			if (!isContentUri) {
				if (!TorrentUtils.isSimpleTorrent(item)) {
					walkdlDir = new File(walkdlDir).getParent();
				}
			}

			Uri walkUri = FileUtils.guessTreeUri(context, walkdlDir, false);
			if (uri.equals(walkUri)) {
				isAskingForPermsFor.add(
						MapUtils.getMapLong(item, TransmissionVars.FIELD_TORRENT_ID, -1));
				if (isAskingForPermsFor.size() < 5) {
					names += MapUtils.getMapString(item,
							TransmissionVars.FIELD_TORRENT_NAME, "??") + "\n";
				}
			}
		}

		FileUtils.askForPathPerms(context, uri, names, permsAuthLauncher);
	}

	private void permsAuthReceived(ActivityResult result) {
		if (VERSION.SDK_INT < VERSION_CODES.KITKAT) {
			return;
		}

		if (isAskingForPermsFor == null) {
			return;
		}

		Intent resultIntent = result.getData();
		Uri uri = resultIntent == null
				|| result.getResultCode() != Activity.RESULT_OK ? null
						: resultIntent.getData();
		if (uri == null) {
			return;
		}

		Context context = requireContext();
		ContentResolver contentResolver = context.getContentResolver();
		contentResolver.takePersistableUriPermission(uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

		List<Long> idsToStart = new ArrayList<>();
		boolean shownWarning = false;
		for (Long torrentId : isAskingForPermsFor) {
			Map<String, Object> item = session.torrent.getCachedTorrent(torrentId);
			if (item == null) {
				continue;
			}

			String dlDir = MapUtils.getMapString(item,
					TransmissionVars.FIELD_TORRENT_DOWNLOAD_DIR, null);

			if (FileUtils.isContentPath(dlDir)) {

				if (!shownWarning) {
					// Compare first torrent's dlDir with the one selected.  Warn if different
					try {
						Uri firstUri = FileUtils.guessTreeUri(context, dlDir, false);
						Uri strippedUri = DocumentsContractCompat.buildTreeDocumentUri(
								uri.getAuthority(),
								DocumentsContractCompat.getTreeDocumentId(uri));

						if (!firstUri.equals(strippedUri)) {

							PathInfo pathInfoWanted = PathInfo.buildPathInfo(
									firstUri.toString());
							PathInfo pathInfoAuthed = PathInfo.buildPathInfo(
									strippedUri.toString());

							AndroidUtilsUI.showDialog(requireActivity(),
									R.string.authorized_wrong_path_title,
									R.string.authorized_wrong_path,
									pathInfoAuthed.getFriendlyName(),
									pathInfoWanted.getFriendlyName());
							shownWarning = true;
						}
					} catch (Throwable t) {
						Log.e(TAG, "permsAuthReceived", t);
					}
				}

				item.put(TransmissionVars.FIELD_TORRENT_RECHECKAUTH, true);

				long errorStat = MapUtils.getMapLong(item,
						TransmissionVars.FIELD_TORRENT_ERROR, TransmissionVars.TR_STAT_OK);
				int status = MapUtils.getMapInt(item,
						TransmissionVars.FIELD_TORRENT_STATUS,
						TransmissionVars.TR_STATUS_STOPPED);
				if (DEBUG) {
					log(TAG,
							"permsAuthReceived: "
									+ item.get(TransmissionVars.FIELD_TORRENT_NAME)
									+ " errorStat=" + errorStat + "; status=" + status);
				}

				if (status == TransmissionVars.TR_STATUS_STOPPED
						&& errorStat == TransmissionVars.TR_STAT_LOCAL_ERROR) {
					idsToStart.add(
							MapUtils.getMapLong(item, TransmissionVars.FIELD_TORRENT_ID, -1));
				}
			} else {
				// Direct -- change path to authorized content uri

				String path;
				if (TorrentUtils.isSimpleTorrent(item)) {
					path = uri.toString();
				} else {
					// uri is a document
					// For non-simple torrents, we've authorized the parent path
					// Get that last bit (usually the name of the torrent, but may not be)
					File dlDirFile = new File(dlDir);
					File file = new AndroidFileHandler().newFile(uri.toString(),
							dlDirFile.getName());
					path = file.toString();

					// For comparison with authorized uri, dlDir must not include last bit
					dlDir = dlDirFile.getParent();
				}

				if (DEBUG) {
					log(TAG,
							"permsAuthReceived: "
									+ item.get(TransmissionVars.FIELD_TORRENT_NAME)
									+ " moving to " + path);
				}

				// check if new uri path represents old direct path
				Uri dlUri = FileUtils.guessTreeUri(context, dlDir, false);
				if (!uri.equals(dlUri)) {
					log(Log.WARN, TAG, "permsAuthReceived: new path isn't same as "
							+ dlDir + " aka " + dlUri);

					if (!shownWarning) {
						shownWarning = true;

						PathInfo pathInfoWanted = PathInfo.buildPathInfo(dlUri.toString());
						PathInfo pathInfoAuthed = PathInfo.buildPathInfo(uri.toString());

						AndroidUtilsUI.showDialog(requireActivity(),
								R.string.authorized_wrong_path_title,
								R.string.authorized_wrong_path,
								pathInfoAuthed.getFriendlyName(),
								pathInfoWanted.getFriendlyName());
					}
					continue;
				}

				session.torrent.moveDataTo(torrentId, path, true);
			}
		}

		if (!idsToStart.isEmpty()) {
			long[] ids = idsToStart.stream().mapToLong(l -> l).toArray();
			if (DEBUG) {
				log(TAG, "permsAuthReceived: Starting " + Arrays.toString(ids)
						+ " after perms auth");
			}
			session.torrent.startTorrents(ids, false);
		}

		session.triggerRefresh(false);
		isAskingForPermsFor = null;
	}
}
