package com.yydcdut.note.presenters.home.impl;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;

import com.yydcdut.note.R;
import com.yydcdut.note.aspect.permission.AspectPermission;
import com.yydcdut.note.bus.CategoryCreateEvent;
import com.yydcdut.note.bus.CategoryMoveEvent;
import com.yydcdut.note.bus.PhotoNoteCreateEvent;
import com.yydcdut.note.bus.PhotoNoteDeleteEvent;
import com.yydcdut.note.entity.Category;
import com.yydcdut.note.entity.PhotoNote;
import com.yydcdut.note.injector.ContextLife;
import com.yydcdut.note.model.compare.ComparatorFactory;
import com.yydcdut.note.model.rx.RxCategory;
import com.yydcdut.note.model.rx.RxPhotoNote;
import com.yydcdut.note.model.rx.RxSandBox;
import com.yydcdut.note.presenters.home.IAlbumPresenter;
import com.yydcdut.note.utils.FilePathUtils;
import com.yydcdut.note.utils.ImageManager.ImageLoaderManager;
import com.yydcdut.note.utils.LocalStorageUtils;
import com.yydcdut.note.utils.PermissionUtils;
import com.yydcdut.note.utils.Utils;
import com.yydcdut.note.utils.YLog;
import com.yydcdut.note.utils.permission.Permission;
import com.yydcdut.note.views.IView;
import com.yydcdut.note.views.home.IAlbumView;

import org.greenrobot.eventbus.EventBus;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.inject.Inject;

import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;

/**
 * Created by yuyidong on 15/11/20.
 */
public class AlbumPresenterImpl implements IAlbumPresenter {

    private IAlbumView mAlbumView;

    private int mCategoryId = -1;
    private int mAlbumSortKind;

    private Context mContext;
    private LocalStorageUtils mLocalStorageUtils;
    private RxCategory mRxCategory;
    private RxPhotoNote mRxPhotoNote;
    private RxSandBox mRxSandBox;

    @Inject
    public AlbumPresenterImpl(@ContextLife("Activity") Context context,
                              RxCategory rxCategory, RxPhotoNote rxPhotoNote, RxSandBox rxSandBox,
                              LocalStorageUtils localStorageUtils) {

        mContext = context;
        mRxCategory = rxCategory;
        mRxPhotoNote = rxPhotoNote;
        mRxSandBox = rxSandBox;
        mLocalStorageUtils = localStorageUtils;
        mAlbumSortKind = mLocalStorageUtils.getSortKind();
    }

    @Override
    public void attachView(IView iView) {
        mAlbumView = (IAlbumView) iView;
        mRxPhotoNote.findByCategoryId(mCategoryId, mAlbumSortKind)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(photoNoteList -> mAlbumView.setAdapter(photoNoteList),
                        (throwable -> YLog.e(throwable)));
        changeTitle();
    }

    private void changeTitle() {
        mRxCategory.getAllCategories()
                .flatMap(categories -> Observable.from(categories))
                .filter(category -> category.getId() == mCategoryId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(category1 -> mAlbumView.setToolBarTitle(category1.getLabel()),
                        (throwable -> YLog.e(throwable)));
    }

    @Override
    public void detachView() {

    }

    @Override
    public IView getIView() {
        return mAlbumView;
    }

    @Override
    public void bindData(int categoryId) {
        mCategoryId = categoryId;
    }

    @Override
    public void checkSandBox() {
        /**
         * 主要针对于拍完照回到这个界面之后判断沙盒里面还要数据没
         */
        new Handler().postDelayed(() -> {
            //todo RxJava有delay的方法，写这的时候还不知道怎么用，等重构完回来改
            mRxSandBox.getNumber()
                    .subscribe(integer -> {
                        if (integer > 0) {
                            mAlbumView.startSandBoxService();
                        }
                    }, (throwable -> YLog.e(throwable)));
        }, 3000);
    }

    @Override
    public void setAlbumSort(int sort) {
        mAlbumSortKind = sort;
        mLocalStorageUtils.setSortKind(mAlbumSortKind);
    }

    @Override
    public void saveAlbumSort() {
        mLocalStorageUtils.setSortKind(mAlbumSortKind);
    }

    @Override
    public int getAlbumSort() {
        return mAlbumSortKind;
    }

    @Override
    public void jump2DetailActivity(int position) {
        mAlbumView.jump2DetailActivity(mCategoryId, position, mAlbumSortKind);
    }

    @Override
    public void updateFromBroadcast(boolean broadcast_process, boolean broadcast_service, boolean broadcast_photo) {
        //当图片数据改变的时候，比如滤镜，Service作图
        //另外个进程发来广播的时候
        //todo  这里可以弄动画，需要计算的过程
        //// TODO: 16/2/19 改为本地广播
        if (broadcast_process || broadcast_service) {
            mRxPhotoNote.refreshByCategoryId(mCategoryId, mAlbumSortKind)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(photoNoteList -> mAlbumView.updateData(photoNoteList),
                            (throwable -> YLog.e(throwable)));
        } else if (broadcast_photo) {
            mAlbumView.notifyDataSetChanged();
        }
    }

    @Override
    public void sortData() {
        mRxPhotoNote.findByCategoryId(mCategoryId, mAlbumSortKind)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(photoNoteList -> mAlbumView.notifyDataSetChanged(),
                        (throwable -> YLog.e(throwable)));
    }

    @Override
    public void changeCategoryWithPhotos(int categoryId) {
        mCategoryId = categoryId;
        mRxPhotoNote.findByCategoryId(mCategoryId, mAlbumSortKind)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(photoNoteList -> mAlbumView.updateData(photoNoteList),
                        (throwable -> YLog.e(throwable)));
        changeTitle();
    }

    @Override
    public void movePhotos2AnotherCategory() {
        mRxCategory.getAllCategories()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(categories -> {
                    final String[] categoryIdStringArray = new String[categories.size()];
                    final String[] categoryLabelArray = new String[categories.size()];
                    for (int i = 0; i < categoryIdStringArray.length; i++) {
                        categoryIdStringArray[i] = categories.get(i).getId() + "";
                        categoryLabelArray[i] = categories.get(i).getLabel();
                    }
                    mAlbumView.showMovePhotos2AnotherCategoryDialog(categoryIdStringArray, categoryLabelArray);
                }, (throwable -> YLog.e(throwable)));
    }

    @Override
    public void changePhotosCategory(int toCategoryId) {
        if (mCategoryId != toCategoryId) {
            mRxPhotoNote.findByCategoryId(mCategoryId, mAlbumSortKind)
                    .observeOn(AndroidSchedulers.mainThread())
                    .map(photoNoteList -> {
                        TreeMap<Integer, PhotoNote> map = getTreeMap();
                        for (int i = 0; i < photoNoteList.size(); i++) {
                            PhotoNote photoNote = photoNoteList.get(i);
                            if (photoNote.isSelected()) {
                                photoNote.setSelected(false);
                                photoNote.setCategoryId(toCategoryId);
                                map.put(i, photoNote);
                            }
                        }
                        int times = 0;
                        for (Map.Entry<Integer, PhotoNote> entry : map.entrySet()) {
                            photoNoteList.remove(entry.getValue());
                            mAlbumView.notifyItemRemoved(entry.getKey() - times);//todo 这个在这里合适吗？觉得严重的不合适
                            mRxPhotoNote.updatePhotoNote(entry.getValue()).subscribe((photoNotes -> {
                            }), (throwable -> YLog.e(throwable)));
                            times++;
                        }
                        return map.size();
                    })
                    .subscribe(integer -> {
                        mRxCategory.updateChangeCategory(mCategoryId, toCategoryId, integer)
                                .subscribe(categories -> {
                                    mRxPhotoNote.refreshByCategoryId(mCategoryId, ComparatorFactory.FACTORY_NOT_SORT).subscribe(
                                            (photoNoteList) -> mAlbumView.updateDataNoChange(photoNoteList),
                                            (throwable -> YLog.e(throwable)));
                                    mRxPhotoNote.refreshByCategoryId(toCategoryId, ComparatorFactory.FACTORY_NOT_SORT).subscribe(
                                            photoNotes -> {
                                            }, (throwable -> YLog.e(throwable))
                                    );
                                    EventBus.getDefault().post(new CategoryMoveEvent());
                                }, (throwable -> YLog.e(throwable)));
                    }, (throwable -> YLog.e(throwable)));
        }
    }

    @Override
    public void deletePhotos() {
        //注意java.util.ConcurrentModificationException at java.util.ArrayList$ArrayListIterator.next(ArrayList.java:573)
        mRxPhotoNote.findByCategoryId(mCategoryId, mAlbumSortKind)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(photoNoteList -> {
                    TreeMap<Integer, PhotoNote> map = getTreeMap();
                    for (int i = 0; i < photoNoteList.size(); i++) {
                        PhotoNote photoNote = photoNoteList.get(i);
                        if (photoNote.isSelected()) {
                            map.put(i, photoNote);
                        }
                    }
                    int times = 0;
                    for (Map.Entry<Integer, PhotoNote> entry : map.entrySet()) {
                        photoNoteList.remove(entry.getValue());
                        mAlbumView.notifyItemRemoved(entry.getKey() - times);
                        times++;
                        mRxPhotoNote.deletePhotoNote(entry.getValue())
                                .subscribe(photoNotes -> {
                                    FilePathUtils.deleteAllFiles(entry.getValue().getPhotoName());
                                    mAlbumView.updateDataNoChange(photoNoteList);
                                }, (throwable -> YLog.e(throwable)));
                    }
                    EventBus.getDefault().post(new PhotoNoteDeleteEvent());
                }, (throwable -> YLog.e(throwable)));
    }

    /**
     * 得到经过Key(Integer)的排序的map
     *
     * @return
     */
    private TreeMap<Integer, PhotoNote> getTreeMap() {
        return new TreeMap<>((lhs, rhs) -> lhs - rhs);
    }

    @Override
    public void createCategory(String newCategoryLabel) {
        mRxCategory.getAllCategories()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(categories -> {
                    int totalNumber = categories.size();
                    if (!TextUtils.isEmpty(newCategoryLabel)) {
                        mRxCategory.saveCategory(newCategoryLabel, 0, totalNumber, true)
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(categories1 -> {
                                    boolean success = false;
                                    for (Category category : categories1) {
                                        if (category.getLabel().equals(newCategoryLabel)) {
                                            mAlbumView.changeActivityListMenuCategoryChecked(category);
                                            EventBus.getDefault().post(new CategoryCreateEvent());
                                            success = true;
                                            break;
                                        }
                                    }
                                    if (!success) {
                                        mAlbumView.showToast(mContext.getResources().getString(R.string.toast_fail));
                                    }
                                }, (throwable -> YLog.e(throwable)));
                    } else {
                        mAlbumView.showToast(mContext.getResources().getString(R.string.toast_fail));
                    }
                }, (throwable -> YLog.e(throwable)));
    }


    @Override
    public void savePhotoFromLocal(final Uri imageUri) {
        PhotoNote photoNote = new PhotoNote(System.currentTimeMillis() + ".jpg", System.currentTimeMillis(),
                System.currentTimeMillis(), "", "", System.currentTimeMillis(),
                System.currentTimeMillis(), mCategoryId);
        mRxPhotoNote.savePhotoNote(photoNote)
                .map(photoNote1 -> {
                    //复制大图
                    ContentResolver cr = mContext.getContentResolver();
                    try {
                        FilePathUtils.copyFile(cr.openInputStream(imageUri), photoNote1.getBigPhotoPathWithoutFile());
                        //保存小图
                        FilePathUtils.saveSmallPhotoFromBigPhoto(photoNote1.getBigPhotoPathWithFile(), photoNote1.getPhotoName());
                    } catch (FileNotFoundException e) {
                        YLog.e(e);
                    } catch (IOException e) {
                        YLog.e(e);
                    }
                    photoNote1.setPaletteColor(Utils.getPaletteColor(ImageLoaderManager.loadImageSync(photoNote.getBigPhotoPathWithFile())));
                    mRxPhotoNote.updatePhotoNote(photoNote1).subscribe(photoNotes -> {
                    }, (throwable -> YLog.e(throwable)));
                    return photoNote1;
                })
                .doOnSubscribe(() -> mAlbumView.showProgressBar())
                .subscribeOn(AndroidSchedulers.mainThread())
                .subscribe(photoNote2 -> {
                    EventBus.getDefault().post(new PhotoNoteCreateEvent());
                    //因为是最新时间，即“图片创建事件”、“图片修改时间”、“笔记创建时间”、“笔记修改时间”，所以要么在最前面，要么在最后面//// TODO: 15/11/20 还是因时间来判断插入到哪里，所以要计算
                    mRxPhotoNote.findByCategoryId(mCategoryId, mAlbumSortKind)
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(photoNoteList -> {
                                mAlbumView.updateData(photoNoteList);
                                switch (mAlbumSortKind) {
                                    case ComparatorFactory.FACTORY_CREATE_CLOSE:
                                    case ComparatorFactory.FACTORY_EDITED_CLOSE:
                                        mAlbumView.notifyItemInserted(photoNoteList.size() - 1);
                                        break;
                                    case ComparatorFactory.FACTORY_CREATE_FAR:
                                    case ComparatorFactory.FACTORY_EDITED_FAR:
                                        mAlbumView.notifyItemInserted(0);
                                        break;
                                }
                                mAlbumView.hideProgressBar();
                            });
                }, (throwable -> YLog.e(throwable)));
    }

    @Override
    public void savePhotoFromSystemCamera() {
        PhotoNote photoNote = new PhotoNote(System.currentTimeMillis() + ".jpg", System.currentTimeMillis(),
                System.currentTimeMillis(), "", "", System.currentTimeMillis(),
                System.currentTimeMillis(), mCategoryId);
        mRxPhotoNote.savePhotoNote(photoNote)
                .map(photoNote1 -> {
                    try {
                        //复制大图
                        FilePathUtils.copyFile(FilePathUtils.getTempFilePath(), photoNote1.getBigPhotoPathWithoutFile());
                        //保存小图
                        FilePathUtils.saveSmallPhotoFromBigPhoto(photoNote.getBigPhotoPathWithFile(), photoNote.getPhotoName());
                    } catch (FileNotFoundException e) {
                        YLog.e(e);
                    } catch (IOException e) {
                        YLog.e(e);
                    }
                    photoNote1.setPaletteColor(Utils.getPaletteColor(ImageLoaderManager.loadImageSync(photoNote.getBigPhotoPathWithFile())));
                    mRxPhotoNote.updatePhotoNote(photoNote1).subscribe(photoNotes -> {
                    }, (throwable -> YLog.e(throwable)));
                    return photoNote1;
                })
                .doOnSubscribe(() -> mAlbumView.showProgressBar())
                .subscribeOn(AndroidSchedulers.mainThread())
                .subscribe(photoNote2 -> {
                    EventBus.getDefault().post(new PhotoNoteCreateEvent());
                    //因为是最新时间，即“图片创建事件”、“图片修改时间”、“笔记创建时间”、“笔记修改时间”，所以要么在最前面，要么在最后面//// TODO: 15/11/20 还是因时间来判断插入到哪里，所以要计算
                    mRxPhotoNote.findByCategoryId(mCategoryId, mAlbumSortKind)
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(photoNoteList -> {
                                mAlbumView.updateData(photoNoteList);
                                switch (mAlbumSortKind) {
                                    case ComparatorFactory.FACTORY_CREATE_CLOSE:
                                    case ComparatorFactory.FACTORY_EDITED_CLOSE:
                                        mAlbumView.notifyItemInserted(photoNoteList.size() - 1);
                                        break;
                                    case ComparatorFactory.FACTORY_CREATE_FAR:
                                    case ComparatorFactory.FACTORY_EDITED_FAR:
                                        mAlbumView.notifyItemInserted(0);
                                        break;
                                }
                                mAlbumView.hideProgressBar();
                            });
                }, (throwable -> YLog.e(throwable)));
    }

    @Override
    public void savePhotosFromGallery(ArrayList<String> pathList) {
        List<PhotoNote> photoNotes = new ArrayList<>(pathList.size());
        for (String path : pathList) {
            PhotoNote photoNote = new PhotoNote(System.currentTimeMillis() + ".jpg", System.currentTimeMillis(),
                    System.currentTimeMillis(), "", "", System.currentTimeMillis(),
                    System.currentTimeMillis(), mCategoryId);
            photoNote.setTag(path);
            photoNotes.add(photoNote);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                YLog.e(e);
            }
        }
        mRxPhotoNote.savePhotoNotes(photoNotes)
                .flatMap(photoNotes1 -> Observable.from(photoNotes))//注意看这里是photoNotes
                .map(photoNote -> {
                    String path = (String) photoNote.getTag();
                    if (!TextUtils.isEmpty(path)) {
                        try {
                            //复制大图
                            FilePathUtils.copyFile(path, photoNote.getBigPhotoPathWithoutFile());
                            //保存小图
                            FilePathUtils.saveSmallPhotoFromBigPhoto(photoNote.getBigPhotoPathWithFile(), photoNote.getPhotoName());
                            photoNote.setPaletteColor(Utils.getPaletteColor(ImageLoaderManager.loadImageSync(photoNote.getBigPhotoPathWithFile())));
                        } catch (IOException e) {
                            YLog.e(e);
                        }
                    }
                    return photoNote;
                })
                .lift((Observable.Operator<Integer, PhotoNote>) subscriber -> new Subscriber<PhotoNote>() {
                    @Override
                    public void onCompleted() {
                        subscriber.onNext(mCategoryId);
                        subscriber.onCompleted();
                    }

                    @Override
                    public void onError(Throwable e) {
                    }

                    @Override
                    public void onNext(PhotoNote photoNote) {
                    }
                })
                .doOnSubscribe(() -> mAlbumView.showProgressBar())
                .subscribeOn(AndroidSchedulers.mainThread())
                .subscribe(integer -> {
                    EventBus.getDefault().post(new PhotoNoteCreateEvent());
                    mRxPhotoNote.refreshByCategoryId(mCategoryId, mAlbumSortKind)
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(photoNoteList -> {
                                mAlbumView.updateData(photoNoteList);
                                mAlbumView.notifyDataSetChanged();
                                mAlbumView.hideProgressBar();
                            }, (throwable -> YLog.e(throwable)));
                }, (throwable -> YLog.e(throwable)));
    }

    @Override
    public void jump2Camera() {
        if (mLocalStorageUtils.getCameraSystem()) {
            mAlbumView.jump2CameraSystemActivity();
        } else {
            getPermissionAndJumpCameraActivity();
        }

    }

    @Override
    public boolean checkStorageEnough() {
        if (!FilePathUtils.isSDCardStoredEnough()) {
            mAlbumView.showToast(mContext.getResources().getString(R.string.no_space));
            return false;
        }
        return true;
    }

    @Override
    public int calculateGridNumber() {
        return mLocalStorageUtils.getAlbumItemNumber();
    }

    @Permission(PermissionUtils.CODE_CAMERA)
    @AspectPermission
    private void getPermissionAndJumpCameraActivity() {
        mAlbumView.jump2CameraActivity(mCategoryId);
    }
}


