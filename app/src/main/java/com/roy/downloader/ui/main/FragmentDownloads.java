package com.roy.downloader.ui.main;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.selection.MutableSelection;
import androidx.recyclerview.selection.SelectionPredicates;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.selection.StorageStrategy;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.roy.downloader.R;
import com.roy.downloader.core.filter.DownloadFilter;
import com.roy.downloader.core.utils.Utils;
import com.roy.downloader.databinding.FFragmentDownloadListBinding;
import com.roy.downloader.ui.BaseAlertDialog;
import com.roy.downloader.ui.details.DialogDownloadDetails;

import java.util.UUID;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/*
 * A base fragment for individual fragment with sorted content (queued and completed downloads)
 */

public abstract class FragmentDownloads extends Fragment implements DownloadListAdapter.ClickListener {
    @SuppressWarnings("unused")
    private static final String TAG = FragmentDownloads.class.getSimpleName();

    private static final String TAG_DOWNLOAD_LIST_STATE = "download_list_state";
    private static final String SELECTION_TRACKER_ID = "selection_tracker_0";
    private static final String TAG_DELETE_DOWNLOADS_DIALOG = "delete_downloads_dialog";
    private static final String TAG_DOWNLOAD_DETAILS = "download_details";

    protected AppCompatActivity activity;
    protected DownloadListAdapter adapter;
    protected LinearLayoutManager layoutManager;
    /* Save state scrolling */
    private Parcelable downloadListState;
    private SelectionTracker<DownloadItem> selectionTracker;
    private ActionMode actionMode;
    protected FFragmentDownloadListBinding binding;
    protected DownloadsViewModel viewModel;
    protected CompositeDisposable disposables = new CompositeDisposable();
    private BaseAlertDialog deleteDownloadsDialog;
    private BaseAlertDialog.SharedViewModel dialogViewModel;
    private final DownloadFilter fragmentDownloadsFilter;

    public FragmentDownloads(DownloadFilter fragmentDownloadsFilter) {
        this.fragmentDownloadsFilter = fragmentDownloadsFilter;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.f_fragment_download_list, container, false);

        adapter = new DownloadListAdapter(this);
        /*
         * A RecyclerView by default creates another copy of the ViewHolder in order to
         * fade the views into each other. This causes the problem because the old ViewHolder gets
         * the payload but then the new one doesn't. So needs to explicitly tell it to reuse the old one.
         */
        DefaultItemAnimator animator = new DefaultItemAnimator() {
            @Override
            public boolean canReuseUpdatedViewHolder(@NonNull RecyclerView.ViewHolder viewHolder) {
                return true;
            }
        };
        layoutManager = new LinearLayoutManager(activity);
        binding.downloadList.setLayoutManager(layoutManager);
        binding.downloadList.setItemAnimator(animator);
        binding.downloadList.setEmptyView(binding.emptyViewDownloadList);
        binding.downloadList.setAdapter(adapter);

        selectionTracker = new SelectionTracker.Builder<>(SELECTION_TRACKER_ID, binding.downloadList, new DownloadListAdapter.KeyProvider(adapter), new DownloadListAdapter.ItemLookup(binding.downloadList), StorageStrategy.createParcelableStorage(DownloadItem.class)).withSelectionPredicate(SelectionPredicates.createSelectAnything()).build();

        selectionTracker.addObserver(new SelectionTracker.SelectionObserver<DownloadItem>() {
            @Override
            public void onSelectionChanged() {
                super.onSelectionChanged();

                if (selectionTracker.hasSelection() && actionMode == null) {
                    actionMode = activity.startSupportActionMode(actionModeCallback);
                    setActionModeTitle(selectionTracker.getSelection().size());

                } else if (!selectionTracker.hasSelection()) {
                    if (actionMode != null) actionMode.finish();
                    actionMode = null;

                } else {
                    setActionModeTitle(selectionTracker.getSelection().size());
                }
            }

            @Override
            public void onSelectionRestored() {
                super.onSelectionRestored();

                actionMode = activity.startSupportActionMode(actionModeCallback);
                setActionModeTitle(selectionTracker.getSelection().size());
            }
        });

        if (savedInstanceState != null) selectionTracker.onRestoreInstanceState(savedInstanceState);
        adapter.setSelectionTracker(selectionTracker);

        return binding.getRoot();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        if (context instanceof AppCompatActivity) activity = (AppCompatActivity) context;
    }

    @Override
    public void onStop() {
        super.onStop();

        disposables.clear();
    }

    @Override
    public void onStart() {
        super.onStart();

        subscribeAlertDialog();
        subscribeForceSortAndFilter();
    }

    private void subscribeAlertDialog() {
        Disposable d = dialogViewModel.observeEvents().subscribe((event) -> {
            if (event.dialogTag == null || !event.dialogTag.equals(TAG_DELETE_DOWNLOADS_DIALOG) || deleteDownloadsDialog == null)
                return;
            switch (event.type) {
                case POSITIVE_BUTTON_CLICKED:
                    Dialog dialog = deleteDownloadsDialog.getDialog();
                    if (dialog != null) {
                        CheckBox withFile = dialog.findViewById(R.id.deleteWithFile);
                        deleteDownloads(withFile.isChecked());
                    }
                    if (actionMode != null) actionMode.finish();
                case NEGATIVE_BUTTON_CLICKED:
                    deleteDownloadsDialog.dismiss();
                    break;
            }
        });
        disposables.add(d);
    }

    private void subscribeForceSortAndFilter() {
        disposables.add(viewModel.onForceSortAndFilter().filter((force) -> force).observeOn(Schedulers.io()).subscribe((force) -> disposables.add(getDownloadSingle())));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (activity == null) activity = (AppCompatActivity) getActivity();

        assert activity != null;
        ViewModelProvider provider = new ViewModelProvider(activity);
        viewModel = provider.get(DownloadsViewModel.class);
        dialogViewModel = provider.get(BaseAlertDialog.SharedViewModel.class);

        FragmentManager fm = getChildFragmentManager();
        deleteDownloadsDialog = (BaseAlertDialog) fm.findFragmentByTag(TAG_DELETE_DOWNLOADS_DIALOG);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (downloadListState != null) layoutManager.onRestoreInstanceState(downloadListState);
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);

        if (savedInstanceState != null)
            downloadListState = savedInstanceState.getParcelable(TAG_DOWNLOAD_LIST_STATE);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        downloadListState = layoutManager.onSaveInstanceState();
        outState.putParcelable(TAG_DOWNLOAD_LIST_STATE, downloadListState);
        selectionTracker.onSaveInstanceState(outState);

        super.onSaveInstanceState(outState);
    }

    protected void subscribeAdapter() {
        disposables.add(observeDownloads());
    }

    public Disposable observeDownloads() {
        return viewModel.observerAllInfoAndPieces().subscribeOn(Schedulers.io()).flatMapSingle((infoAndPiecesList) -> Flowable.fromIterable(infoAndPiecesList).filter(fragmentDownloadsFilter).filter(viewModel.getDownloadFilter()).map(DownloadItem::new).sorted(viewModel.getSorting()).toList()).observeOn(AndroidSchedulers.mainThread()).subscribe(adapter::submitList, (Throwable t) -> {
            Log.e(TAG, "Getting info and pieces error: " + Log.getStackTraceString(t));
        });
    }

    public Disposable getDownloadSingle() {
        return viewModel.getAllInfoAndPiecesSingle().subscribeOn(Schedulers.io()).flatMap((infoAndPiecesList) -> Observable.fromIterable(infoAndPiecesList).filter(fragmentDownloadsFilter).filter(viewModel.getDownloadFilter()).map(DownloadItem::new).sorted(viewModel.getSorting()).toList()).observeOn(AndroidSchedulers.mainThread()).subscribe(adapter::submitList, (Throwable t) -> {
            Log.e(TAG, "Getting info and pieces error: " + Log.getStackTraceString(t));
        });
    }

    @Override
    public abstract void onItemClicked(@NonNull DownloadItem item);

    private void setActionModeTitle(int itemCount) {
        actionMode.setTitle(String.valueOf(itemCount));
    }

    private final ActionMode.Callback actionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.menu_download_list_action_mode, menu);

            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.deleteMenu -> deleteDownloadsDialog();
                case R.id.shareMenu -> {
                    shareDownloads();
                    mode.finish();
                }
                case R.id.selectAllMenu -> selectAllDownloads();
                case R.id.shareUrlMenu -> {
                    shareUrl();
                    mode.finish();
                }
            }

            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            selectionTracker.clearSelection();
        }
    };

    private void deleteDownloadsDialog() {
        if (!isAdded()) return;

        FragmentManager fm = getChildFragmentManager();
        if (fm.findFragmentByTag(TAG_DELETE_DOWNLOADS_DIALOG) == null) {
            deleteDownloadsDialog = BaseAlertDialog.newInstance(getString(R.string.deleting), (selectionTracker.getSelection().size() > 1 ? getString(R.string.delete_selected_downloads) : getString(R.string.delete_selected_download)), R.layout.dlg_dialog_delete_downloads, getString(R.string.ok), getString(R.string.cancel), null, false);

            deleteDownloadsDialog.show(fm, TAG_DELETE_DOWNLOADS_DIALOG);
        }
    }

    private void deleteDownloads(boolean withFile) {
        MutableSelection<DownloadItem> selections = new MutableSelection<>();
        selectionTracker.copySelection(selections);

        disposables.add(Observable.fromIterable(selections).map((selection -> selection.info)).toList().subscribe((infoList) -> viewModel.deleteDownloads(infoList, withFile)));
    }

    private void shareDownloads() {
        MutableSelection<DownloadItem> selections = new MutableSelection<>();
        selectionTracker.copySelection(selections);

        disposables.add(Observable.fromIterable(selections).toList().subscribe((items) -> {
            Intent intent = Utils.makeFileShareIntent(activity.getApplicationContext(), items);
            if (intent != null) {
                startActivity(Intent.createChooser(intent, getString(R.string.share_via)));
            } else {
                Toast.makeText(activity.getApplicationContext(), getResources().getQuantityString(R.plurals.unable_sharing, items.size()), Toast.LENGTH_SHORT).show();
            }
        }));
    }

    @SuppressLint("RestrictedApi")
    private void selectAllDownloads() {
        int n = adapter.getItemCount();
        if (n > 0) {
            selectionTracker.startRange(0);
            selectionTracker.extendRange(adapter.getItemCount() - 1);
        }
    }

    private void shareUrl() {
        MutableSelection<DownloadItem> selections = new MutableSelection<>();
        selectionTracker.copySelection(selections);

        disposables.add(Observable.fromIterable(selections).map((item) -> item.info.url).toList().subscribe((urlList) -> {
            startActivity(Intent.createChooser(Utils.makeShareUrlIntent(urlList), getString(R.string.share_via)));
        }));
    }

    protected void showDetailsDialog(UUID id) {
        if (!isAdded()) return;

        FragmentManager fm = getChildFragmentManager();
        if (fm.findFragmentByTag(TAG_DOWNLOAD_DETAILS) == null) {
            DialogDownloadDetails details = DialogDownloadDetails.newInstance(id);
            details.show(fm, TAG_DOWNLOAD_DETAILS);
        }
    }
}
