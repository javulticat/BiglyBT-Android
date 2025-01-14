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

package com.biglybt.android.client;

import android.Manifest.permission;
import android.content.Context;
import android.content.Intent;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;

import androidx.annotation.*;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.biglybt.android.client.AppCompatActivityM.PermissionRequestResults;
import com.biglybt.android.client.AppCompatActivityM.PermissionResultHandler;
import com.biglybt.android.client.activity.IntentHandler;
import com.biglybt.android.client.activity.TorrentViewActivity;
import com.biglybt.android.client.dialog.DialogFragmentBiglyBTCoreProfile;
import com.biglybt.android.client.dialog.DialogFragmentBiglyBTRemoteProfile;
import com.biglybt.android.client.dialog.DialogFragmentGenericRemoteProfile;
import com.biglybt.android.client.rpc.RPC;
import com.biglybt.android.client.session.RemoteProfile;
import com.biglybt.android.client.session.RemoteProfileFactory;
import com.biglybt.android.client.session.SessionManager;
import com.biglybt.android.util.JSONUtils;
import com.biglybt.util.Thunk;

import java.util.List;
import java.util.Map;

public class RemoteUtils
{
	public static final String KEY_REMOTE_JSON = "remote.json";

	public static final String KEY_REQ_PW = "reqPW";

	//private static final String TAG = "RemoteUtils";
	public static String lastOpenDebug = null;

	/**
	 * 
	 * @return true if opened immediately
	 */
	public static boolean openRemote(final AppCompatActivityM activity,
			final RemoteProfile remoteProfile, final boolean isMain,
			final boolean closeActivityOnSuccess) {
		OffThread.runOffUIThread(() -> {
			AppPreferences appPreferences = BiglyBTApp.getAppPreferences();

			if (appPreferences.getRemote(remoteProfile.getID()) == null) {
				appPreferences.addRemoteProfile(remoteProfile);
			}
		});

		List<String> requiredPermissions = remoteProfile.getRequiredPermissions();
		if (requiredPermissions.size() > 0) {
			return activity.requestPermissions(
					requiredPermissions.toArray(new String[0]),
					new PermissionResultHandler() {
						@WorkerThread
						@Override
						public void onAllGranted() {
							OffThread.runOnUIThread(() -> {

								if (closeActivityOnSuccess && !isMain) {
									activity.finish();
								}
								reallyOpenRemote(activity, remoteProfile, isMain);
							});
						}

						@WorkerThread
						@Override
						public void onSomeDenied(PermissionRequestResults results) {
							List<String> denies = results.getDenies();
							if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
								denies.remove(permission.POST_NOTIFICATIONS);
							}
							if (denies.size() > 0) {
								AndroidUtilsUI.showDialog(activity, R.string.permission_denied,
										R.string.error_client_requires_permissions);
							} else {
								OffThread.runOnUIThread(() -> {
									if (closeActivityOnSuccess && !isMain) {
										activity.finish();
									}
									reallyOpenRemote(activity, remoteProfile, isMain);
								});
							}
						}
					});
		}

		if (closeActivityOnSuccess && !isMain) {
			activity.finish();
		}
		reallyOpenRemote(activity, remoteProfile, isMain);
		return true;
	}

	@Thunk
	@UiThread
	static void reallyOpenRemote(AppCompatActivityM activity,
			RemoteProfile remoteProfile, boolean isMain) {

		Intent myIntent = new Intent(activity.getIntent());
		myIntent.setAction(Intent.ACTION_VIEW);
		myIntent.setFlags(
				myIntent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
		myIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
		if (isMain) {
			myIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
			// Scenario:
			// User has multiple remote hosts.
			// User clicks on torrent link in browser.
			// User is displayed remote selector activity (IntentHandler) and picks
			// Remote activity is opened, torrent is added
			// We want the back button to go back to the browser.  Going back to
			// the remote selector would be confusing (especially if they then chose
			// another remote!)
			myIntent.addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
			activity.finish();
		}
		myIntent.setClass(activity, TorrentViewActivity.class);

		myIntent.putExtra(SessionManager.BUNDLE_KEY, remoteProfile.getID());

		lastOpenDebug = AndroidUtils.getCompressedStackTrace();

		activity.startActivity(myIntent);
	}

	@AnyThread
	public static void openRemoteList(Context context) {
		Intent myIntent = new Intent();
		myIntent.setAction(Intent.ACTION_VIEW);
		myIntent.setFlags(
				Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_CLEAR_TOP
						| Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
		myIntent.setClass(context, IntentHandler.class);
		context.startActivity(myIntent);
	}

	public static void editProfile(RemoteProfile remoteProfile,
			FragmentManager fm, boolean reqPW) {
		DialogFragment dlg;

		int remoteType = remoteProfile.getRemoteType();
		switch (remoteType) {
			case RemoteProfile.TYPE_CORE:
				dlg = new DialogFragmentBiglyBTCoreProfile();
				break;
			case RemoteProfile.TYPE_LOOKUP:
				dlg = new DialogFragmentBiglyBTRemoteProfile();
				break;
			default:
				dlg = new DialogFragmentGenericRemoteProfile();
				break;
		}
		Bundle args = new Bundle();
		Map<?, ?> profileAsMap = remoteProfile.getAsMap(false);
		String profileAsJSON = JSONUtils.encodeToJSON(profileAsMap);
		args.putString(SessionManager.BUNDLE_KEY, remoteProfile.getID());
		args.putSerializable(KEY_REMOTE_JSON, profileAsJSON);
		args.putBoolean(KEY_REQ_PW, reqPW);
		dlg.setArguments(args);
		AndroidUtilsUI.showDialog(dlg, fm, "GenericRemoteProfile");
	}

	public interface OnCoreProfileCreated
	{
		@AnyThread
		void onCoreProfileCreated(RemoteProfile coreProfile,
				boolean alreadyCreated);
	}

	public static void createCoreProfile(@NonNull final FragmentActivity activity,
			final OnCoreProfileCreated l) {
		RemoteProfile coreProfile = RemoteUtils.getCoreProfile();
		if (coreProfile != null) {
			if (l != null) {
				l.onCoreProfileCreated(coreProfile, true);
			}
			return;
		}

		RemoteProfile localProfile = RemoteProfileFactory.create(
				RemoteProfile.TYPE_CORE);
		localProfile.setHost("localhost");
		localProfile.setPort(RPC.LOCAL_BIGLYBT_PORT);
		localProfile.setNick(activity.getString(R.string.local_name,
				AndroidUtils.getFriendlyDeviceName()));
		localProfile.setUpdateInterval(2);

		if (l != null) {
			l.onCoreProfileCreated(localProfile, false);
		}
	}

	public static RemoteProfile getCoreProfile() {
		AppPreferences appPreferences = BiglyBTApp.getAppPreferences();
		RemoteProfile[] remotes = appPreferences.getRemotes();
		RemoteProfile coreProfile = null;
		for (RemoteProfile remoteProfile : remotes) {
			if (remoteProfile.getRemoteType() == RemoteProfile.TYPE_CORE) {
				coreProfile = remoteProfile;
				break;
			}
		}
		return coreProfile;
	}
}
