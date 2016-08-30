/**
 *  Copyright 2014 Ryszard Wiśniewski <brut.alll@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package brut.androlib;

import brut.androlib.err.InFileNotFoundException;
import brut.androlib.err.OutDirExistsException;
import brut.androlib.err.UndefinedResObject;
import brut.androlib.meta.MetaInfo;
import brut.androlib.meta.PackageInfo;
import brut.androlib.meta.UsesFramework;
import brut.androlib.meta.VersionInfo;
import brut.androlib.res.AndrolibResources;
import brut.androlib.res.data.ResPackage;
import brut.androlib.res.data.ResTable;
import brut.androlib.res.util.ExtFile;
import brut.androlib.res.xml.ResXmlPatcher;
import brut.common.BrutException;
import brut.directory.DirectoryException;
import brut.util.OS;
import com.google.common.base.Strings;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * @author Ryszard Wiśniewski <brut.alll@gmail.com>
 */
public class ApkDecoder {
    public ApkDecoder() {
        this(new Androlib());
    }

    public ApkDecoder(Androlib androlib) {
        mAndrolib = androlib;
    }

    public ApkDecoder(File apkFile) {
        this(apkFile, new Androlib());
    }

    public ApkDecoder(File apkFile, Androlib androlib) {
        mAndrolib = androlib;
        setApkFile(apkFile);
    }

    public void setApkFile(File apkFile) {
        mApkFile = new ExtFile(apkFile);
        mResTable = null;
    }

    public void setOutDir(File outDir) throws AndrolibException {
        mOutDir = outDir;
    }

    public void setApi(int api) {
        mApi = api;
    }

    public void decode() throws AndrolibException, IOException, DirectoryException { //主要的apk解包函数
        File outDir = getOutDir();  //初始化输出文件
        AndrolibResources.sKeepBroken = mKeepBrokenResources; // -k选项

        if (!mForceDelete && outDir.exists()) { // -f选项
            throw new OutDirExistsException();
        }

        if (!mApkFile.isFile() || !mApkFile.canRead()) {//判断文件是否存在及可读
            throw new InFileNotFoundException();
        }

        try {
            OS.rmdir(outDir);
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }
        outDir.mkdirs();

        LOGGER.info("Using Apktool " + Androlib.getVersion() + " on " + mApkFile.getName());

        if (hasResources()) {//判断是否有resource.arsc
            switch (mDecodeResources) {
                case DECODE_RESOURCES_NONE:   //-r选项,即没有res
                    mAndrolib.decodeResourcesRaw(mApkFile, outDir);//直接复制raw,即复制原始文件
                    break;
                case DECODE_RESOURCES_FULL://需要解码的 //默认选项
                    setTargetSdkVersion(); //设置目标sdk版本
                    setAnalysisMode(mAnalysisMode, true);//开始分析模式

                    if (hasManifest()) { //是否有manifest文件的
                        //解码文件with resource
                        mAndrolib.decodeManifestWithResources(mApkFile, outDir, getResTable());
                    }
                    mAndrolib.decodeResourcesFull(mApkFile, outDir, getResTable()); //解码,可跟进去学习
                    break;
            }
        } else {
            // if there's no resources.asrc, decode the manifest without looking
            // up attribute references
            if (hasManifest()) {
                switch (mDecodeResources) {
                    case DECODE_RESOURCES_NONE:
                        mAndrolib.decodeManifestRaw(mApkFile, outDir); //直接复制
                        break;
                    case DECODE_RESOURCES_FULL:
                        mAndrolib.decodeManifestFull(mApkFile, outDir,
                                getResTable());//xml解码关键点
                        break;
                }
            }
        }

        if (hasSources()) { //dex 文件处理
            switch (mDecodeSources) {
                case DECODE_SOURCES_NONE://直接复制，不生成smali
                    mAndrolib.decodeSourcesRaw(mApkFile, outDir, "classes.dex");
                    break;
                case DECODE_SOURCES_SMALI://反编译成smali入口点
                    mAndrolib.decodeSourcesSmali(mApkFile, outDir, "classes.dex", mBakDeb, mApi);
                    break;
            }
        }
        //下面同上
        if (hasMultipleSources()) {//多个dex文件处理
            // foreach unknown dex file in root, lets disassemble it
            Set<String> files = mApkFile.getDirectory().getFiles(true);
            for (String file : files) {
                if (file.endsWith(".dex")) {
                    if (! file.equalsIgnoreCase("classes.dex")) {
                        switch(mDecodeSources) {
                            case DECODE_SOURCES_NONE:
                                mAndrolib.decodeSourcesRaw(mApkFile, outDir, file);
                                break;
                            case DECODE_SOURCES_SMALI:
                                mAndrolib.decodeSourcesSmali(mApkFile, outDir, file, mBakDeb, mApi);
                                break;
                        }
                    }
                }
            }
        }

        mAndrolib.decodeRawFiles(mApkFile, outDir);  //处理原始文件 assets、libs
        mAndrolib.decodeUnknownFiles(mApkFile, outDir, mResTable);//处理未知格式文件，即不是 "classes.dex", "AndroidManifest.xml", "resources.arsc", "res", "r", "R","lib", "libs", "assets", "META-INF"
        mUncompressedFiles = new ArrayList<String>();
        mAndrolib.recordUncompressedFiles(mApkFile, mUncompressedFiles); //记录没有解压缩的文件
        mAndrolib.writeOriginalFiles(mApkFile, outDir); //复制原始文件
        writeMetaFile();//写meta文件
    }

    public void setDecodeSources(short mode) throws AndrolibException {
        if (mode != DECODE_SOURCES_NONE && mode != DECODE_SOURCES_SMALI) {
            throw new AndrolibException("Invalid decode sources mode: " + mode);
        }
        mDecodeSources = mode;
    }

    public void setDecodeResources(short mode) throws AndrolibException {
        if (mode != DECODE_RESOURCES_NONE && mode != DECODE_RESOURCES_FULL) {
            throw new AndrolibException("Invalid decode resources mode");
        }
        mDecodeResources = mode;
    }

    public void setAnalysisMode(boolean mode, boolean pass) throws AndrolibException{
        mAnalysisMode = mode;

        // only set mResTable, once it exists
        if (pass) {
            if (mResTable == null) {
                mResTable = getResTable();
            }
            mResTable.setAnalysisMode(mode);
        }
    }

    public void setTargetSdkVersion() throws AndrolibException, IOException {
        if (mResTable == null) {
            mResTable = mAndrolib.getResTable(mApkFile);
        }

        Map<String, String> sdkInfo = mResTable.getSdkInfo();
        if (sdkInfo.get("targetSdkVersion") != null) {
            mApi = Integer.parseInt(sdkInfo.get("targetSdkVersion"));
        }
    }

    public void setBaksmaliDebugMode(boolean bakdeb) {
        mBakDeb = bakdeb;
    }

    public void setForceDelete(boolean forceDelete) {
        mForceDelete = forceDelete;
    }

    public void setFrameworkTag(String tag) throws AndrolibException {
        mAndrolib.apkOptions.frameworkTag = tag;
    }

    public void setKeepBrokenResources(boolean keepBrokenResources) {
        mKeepBrokenResources = keepBrokenResources;
    }

    public void setFrameworkDir(String dir) {
        mAndrolib.apkOptions.frameworkFolderLocation = dir;
    }

    public ResTable getResTable() throws AndrolibException {
        if (mResTable == null) {
            boolean hasResources = hasResources();
            boolean hasManifest = hasManifest();
            if (! (hasManifest || hasResources)) {
                throw new AndrolibException(
                        "Apk doesn't contain either AndroidManifest.xml file or resources.arsc file");
            }
            mResTable = mAndrolib.getResTable(mApkFile, hasResources);
        }
        return mResTable;
    }

    public boolean hasSources() throws AndrolibException {
        try {
            return mApkFile.getDirectory().containsFile("classes.dex");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    public boolean hasMultipleSources() throws AndrolibException {
        try {
            Set<String> files = mApkFile.getDirectory().getFiles(false);
            for (String file : files) {
                if (file.endsWith(".dex")) {
                    if (! file.equalsIgnoreCase("classes.dex")) {
                        return true;
                    }
                }
            }

            return false;
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    public boolean hasManifest() throws AndrolibException {
        try {
            return mApkFile.getDirectory().containsFile("AndroidManifest.xml");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    public boolean hasResources() throws AndrolibException {
        try {
            return mApkFile.getDirectory().containsFile("resources.arsc");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    public final static short DECODE_SOURCES_NONE = 0x0000; //没有src资源，所以设置为NONE
    public final static short DECODE_SOURCES_SMALI = 0x0001;

    public final static short DECODE_RESOURCES_NONE = 0x0100;//256 //没有res资源，所以设置为NONE
    public final static short DECODE_RESOURCES_FULL = 0x0101;//257

    private File getOutDir() throws AndrolibException {
        if (mOutDir == null) {
            throw new AndrolibException("Out dir not set");
        }
        return mOutDir;
    }

    private void writeMetaFile() throws AndrolibException {
        MetaInfo meta = new MetaInfo();
        meta.version = Androlib.getVersion();
        meta.apkFileName = mApkFile.getName();

        if (mDecodeResources != DECODE_RESOURCES_NONE && (hasManifest() || hasResources())) {
            meta.isFrameworkApk = mAndrolib.isFrameworkApk(getResTable());
            putUsesFramework(meta);
            putSdkInfo(meta);
            putPackageInfo(meta);
            putVersionInfo(meta);
            putSharedLibraryInfo(meta);
        }
        putUnknownInfo(meta);
        putFileCompressionInfo(meta);

        mAndrolib.writeMetaFile(mOutDir, meta);
    }

    private void putUsesFramework(MetaInfo meta) throws AndrolibException {
        Set<ResPackage> pkgs = getResTable().listFramePackages();
        if (pkgs.isEmpty()) {
            return;
        }

        Integer[] ids = new Integer[pkgs.size()];
        int i = 0;
        for (ResPackage pkg : pkgs) {
            ids[i++] = pkg.getId();
        }
        Arrays.sort(ids);

        meta.usesFramework = new UsesFramework();
        meta.usesFramework.ids = Arrays.asList(ids);

        if (mAndrolib.apkOptions.frameworkTag != null) {
            meta.usesFramework.tag = mAndrolib.apkOptions.frameworkTag;
        }
    }

    private void putSdkInfo(MetaInfo meta) throws AndrolibException {
        Map<String, String> info = getResTable().getSdkInfo();
        if (info.size() > 0) {
            meta.sdkInfo = info;
        }
    }

    private void putPackageInfo(MetaInfo meta) throws AndrolibException {
        String renamed = getResTable().getPackageRenamed();
        String original = getResTable().getPackageOriginal();

        int id = getResTable().getPackageId();
        try {
            id = getResTable().getPackage(renamed).getId();
        } catch (UndefinedResObject ignored) {}

        if (Strings.isNullOrEmpty(original)) {
            return;
        }

        meta.packageInfo = new PackageInfo();

        // only put rename-manifest-package into apktool.yml, if the change will be required
        if (!renamed.equalsIgnoreCase(original)) {
            meta.packageInfo.renameManifestPackage = renamed;
        }
        meta.packageInfo.forcedPackageId = String.valueOf(id);
    }

    private void putVersionInfo(MetaInfo meta) throws AndrolibException {
        VersionInfo info = getResTable().getVersionInfo();
        String refValue = ResXmlPatcher.pullValueFromStrings(mOutDir, info.versionName);
        if (refValue != null) {
            info.versionName = refValue;
        }
        meta.versionInfo = info;
    }

    private void putUnknownInfo(MetaInfo meta) throws AndrolibException {
        meta.unknownFiles = mAndrolib.mResUnknownFiles.getUnknownFiles();
    }

    private void putFileCompressionInfo(MetaInfo meta) throws AndrolibException {
        if (!mUncompressedFiles.isEmpty()) {
            meta.doNotCompress = mUncompressedFiles;
        }
    }

    private void putSharedLibraryInfo(MetaInfo meta) throws AndrolibException {
        meta.sharedLibrary = mResTable.getSharedLibrary();
    }

    private final Androlib mAndrolib;

    private final static Logger LOGGER = Logger.getLogger(Androlib.class.getName());

    private ExtFile mApkFile;
    private File mOutDir;
    private ResTable mResTable;
    private short mDecodeSources = DECODE_SOURCES_SMALI;
    private short mDecodeResources = DECODE_RESOURCES_FULL;
    private boolean mForceDelete = false;
    private boolean mKeepBrokenResources = false;
    private boolean mBakDeb = true;
    private Collection<String> mUncompressedFiles;
    private boolean mAnalysisMode = false;
    private int mApi = 15;
}
