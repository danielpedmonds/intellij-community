/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.idea.svn;

import com.intellij.lifecycle.PeriodicalTasksCloser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManagerQueue;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.committed.VcsConfigurationChangeListener;
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl;
import com.intellij.openapi.vcs.impl.VcsInitObject;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.svn.branchConfig.*;
import org.jetbrains.idea.svn.integrate.SvnBranchItem;

import java.io.File;
import java.util.*;

/**
 * @author yole
 */
@State(
  name = "SvnBranchConfigurationManager",
  storages = {
    @Storage(
      file = StoragePathMacros.PROJECT_FILE
    )}
)
public class SvnBranchConfigurationManager implements PersistentStateComponent<SvnBranchConfigurationManager.ConfigurationBean> {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.idea.svn.SvnBranchConfigurationManager");
  private final Project myProject;
  private final ProjectLevelVcsManager myVcsManager;
  private final SvnLoadedBrachesStorage myStorage;
  private final ProgressManagerQueue myBranchesLoader;

  public SvnBranchConfigurationManager(final Project project,
                                       final ProjectLevelVcsManager vcsManager,
                                       final SvnLoadedBrachesStorage storage) {
    myProject = project;
    myVcsManager = vcsManager;
    myStorage = storage;
    myBranchesLoader = new ProgressManagerQueue(myProject, "Subversion Branches Preloader");
    // TODO: Seems that ProgressManagerQueue is not suitable here at least for some branches loading tasks. For instance,
    // TODO: for DefaultConfigLoader it would be better to run modal cancellable task - so branches structure could be detected and
    // TODO: shown in dialog. Currently when "Configure Branches" is invoked for the first time - no branches are shown.
    // TODO: If "Cancel" is pressed and "Configure Branches" invoked once again - already detected (in background) branches are shown.
    ((ProjectLevelVcsManagerImpl) vcsManager).addInitializationRequest(VcsInitObject.BRANCHES, new Runnable() {
      @Override
      public void run() {
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          @Override
          public void run() {
            if (myProject.isDisposed()) return;
            myBranchesLoader.start();
          }
        });
      }
    });
    myBunch = new NewRootBunch(project, myBranchesLoader);
  }

  public static SvnBranchConfigurationManager getInstance(final Project project) {
    return PeriodicalTasksCloser.getInstance().safeGetService(project, SvnBranchConfigurationManager.class);
  }

  public static class ConfigurationBean {
    public Map<String, SvnBranchConfiguration> myConfigurationMap = new TreeMap<String, SvnBranchConfiguration>();
    /**
     * version of "support SVN in IDEA". for features tracking. should grow
     */
    public Long myVersion;
    public boolean mySupportsUserInfoFilter;
  }

  public Long getSupportValue() {
    return myConfigurationBean.myVersion;
  }

  private ConfigurationBean myConfigurationBean = new ConfigurationBean();
  private final NewRootBunch myBunch;

  public SvnBranchConfigurationNew get(@NotNull final VirtualFile vcsRoot) throws VcsException {
    return myBunch.getConfig(vcsRoot);
  }

  public NewRootBunch getSvnBranchConfigManager() {
    return myBunch;
  }

  public void setConfiguration(final VirtualFile vcsRoot, final SvnBranchConfigurationNew configuration) {
    myBunch.updateForRoot(vcsRoot, new InfoStorage<SvnBranchConfigurationNew>(configuration, InfoReliability.setByUser),
                          new BranchesPreloader(myProject, myBunch, vcsRoot, myBranchesLoader));

    SvnBranchMapperManager.getInstance().notifyBranchesChanged(myProject, vcsRoot, configuration);

    final MessageBus messageBus = myProject.getMessageBus();
    messageBus.syncPublisher(VcsConfigurationChangeListener.BRANCHES_CHANGED).execute(myProject, vcsRoot);
  }

  public ConfigurationBean getState() {
    final ConfigurationBean result = new ConfigurationBean();
    result.myVersion = myConfigurationBean.myVersion;
    final UrlSerializationHelper helper = new UrlSerializationHelper(SvnVcs.getInstance(myProject));

    for (VirtualFile root : myBunch.getMapCopy().keySet()) {
      final String key = root.getPath();
      final SvnBranchConfigurationNew configOrig = myBunch.getConfig(root);
      final SvnBranchConfiguration configuration =
        new SvnBranchConfiguration(configOrig.getTrunkUrl(), configOrig.getBranchUrls(), configOrig.isUserinfoInUrl());

      result.myConfigurationMap.put(key, helper.prepareForSerialization(configuration));
    }
    result.mySupportsUserInfoFilter = true;
    return result;
  }

  public void loadState(final ConfigurationBean object) {
    final UrlSerializationHelper helper = new UrlSerializationHelper(SvnVcs.getInstance(myProject));
    final Map<String, SvnBranchConfiguration> map = object.myConfigurationMap;
    final LocalFileSystem lfs = LocalFileSystem.getInstance();

    final Set<Pair<VirtualFile, SvnBranchConfigurationNew>> whatToInit = new HashSet<Pair<VirtualFile, SvnBranchConfigurationNew>>();
    for (Map.Entry<String, SvnBranchConfiguration> entry : map.entrySet()) {
      final SvnBranchConfiguration configuration = entry.getValue();
      final VirtualFile root = lfs.refreshAndFindFileByIoFile(new File(entry.getKey()));
      if (root == null) {
        LOG.info("root not found: " + entry.getKey());
        continue;
      }

      final SvnBranchConfiguration configToConvert;
      if ((! myConfigurationBean.mySupportsUserInfoFilter) || configuration.isUserinfoInUrl()) {
        configToConvert = helper.afterDeserialization(entry.getKey(), configuration);
      } else {
        configToConvert = configuration;
      }
      final SvnBranchConfigurationNew newConfig = new SvnBranchConfigurationNew();
      newConfig.setTrunkUrl(configToConvert.getTrunkUrl());
      newConfig.setUserinfoInUrl(configToConvert.isUserinfoInUrl());
      for (String branchUrl : configToConvert.getBranchUrls()) {
        List<SvnBranchItem> stored = getStored(branchUrl);
        if (stored != null && ! stored.isEmpty()) {
          newConfig.addBranches(branchUrl, new InfoStorage<List<SvnBranchItem>>(stored, InfoReliability.setByUser));
        } else {
          whatToInit.add(Pair.create(root, newConfig));
          newConfig.addBranches(branchUrl, new InfoStorage<List<SvnBranchItem>>(new ArrayList<SvnBranchItem>(), InfoReliability.empty));
        }
      }

      myBunch.updateForRoot(root, new InfoStorage<SvnBranchConfigurationNew>(newConfig, InfoReliability.setByUser), null);
    }
    ((ProjectLevelVcsManagerImpl) myVcsManager).addInitializationRequest(VcsInitObject.BRANCHES, new Runnable() {
      public void run() {
        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
          public void run() {
            try {
              for (Pair<VirtualFile, SvnBranchConfigurationNew> pair : whatToInit) {
                final BranchesPreloader branchesPreloader = new BranchesPreloader(myProject, myBunch, pair.getFirst(), myBranchesLoader);
                branchesPreloader.setAll(true);
                branchesPreloader.loadImpl(null, pair.getSecond());
              }
            }
            catch (ProcessCanceledException e) {
              //
            }
          }
        });
      }
    });
    object.myConfigurationMap.clear();
    myConfigurationBean = object;
  }

  private List<SvnBranchItem> getStored(String branchUrl) {
    Collection<SvnBranchItem> collection = myStorage.get(branchUrl);
    if (collection == null) return null;
    final List<SvnBranchItem> items = new ArrayList<SvnBranchItem>(collection);
    Collections.sort(items);
    return items;
  }
}
