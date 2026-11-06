package org.telegram.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.function.Consumer;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.BaseFragment;

/** Shared entry point for cloud originals in the grid and in the storage channel. */
public final class GhostCloudPreview {
    public static boolean open(BaseFragment fragment,MessageObject message){
        GhostCloudStore.Photo photo=GhostCloud.photoFor(message);if(photo==null)return false;
        open(fragment,GhostCloud.get(message.currentAccount),photo,message);return true;
    }
    public static void open(BaseFragment fragment,GhostCloud cloud,GhostCloudStore.Photo photo,MessageObject message){
        if(photo.message==null&&message!=null)photo.message=GhostCloudStore.encode(message.messageOwner);
        open(fragment,cloud,new ArrayList<>(Collections.singletonList(photo)),0,null);
    }
    public static void open(BaseFragment fragment,GhostCloud cloud,ArrayList<GhostCloudStore.Photo> photos,int index,Consumer<GhostCloudStore.Photo> details){
        GhostCloudGalleryPreview.open(fragment,cloud,photos,index,details);
    }
    private GhostCloudPreview(){}
}
