package com.pmease.gitplex.web.page.repository.pullrequest.requestdetail.compare;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.event.IEvent;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.request.resource.CssResourceReference;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.internal.storage.file.FileRepository;

import com.google.common.base.Preconditions;
import com.pmease.commons.git.BlobIdent;
import com.pmease.commons.git.Commit;
import com.pmease.commons.git.GitUtils;
import com.pmease.commons.hibernate.HibernateUtils;
import com.pmease.commons.hibernate.dao.Dao;
import com.pmease.commons.loader.InheritableThreadLocalData;
import com.pmease.commons.wicket.ajaxlistener.ConfirmLeaveListener;
import com.pmease.commons.wicket.ajaxlistener.IndicateLoadingListener;
import com.pmease.commons.wicket.behavior.StickyBehavior;
import com.pmease.commons.wicket.behavior.TooltipBehavior;
import com.pmease.commons.wicket.behavior.dropdown.DropdownBehavior;
import com.pmease.commons.wicket.behavior.dropdown.DropdownPanel;
import com.pmease.commons.wicket.behavior.menu.MenuBehavior;
import com.pmease.commons.wicket.behavior.menu.MenuItem;
import com.pmease.commons.wicket.behavior.menu.MenuPanel;
import com.pmease.commons.wicket.websocket.WebSocketRenderBehavior;
import com.pmease.gitplex.core.GitPlex;
import com.pmease.gitplex.core.comment.InlineComment;
import com.pmease.gitplex.core.comment.InlineCommentSupport;
import com.pmease.gitplex.core.manager.PullRequestCommentManager;
import com.pmease.gitplex.core.manager.UserManager;
import com.pmease.gitplex.core.model.IntegrationPreview;
import com.pmease.gitplex.core.model.PullRequest;
import com.pmease.gitplex.core.model.PullRequestComment;
import com.pmease.gitplex.core.model.PullRequestUpdate;
import com.pmease.gitplex.core.model.Repository;
import com.pmease.gitplex.core.model.User;
import com.pmease.gitplex.web.component.comment.CommentRemoved;
import com.pmease.gitplex.web.component.diff.revision.RevisionDiffPanel;
import com.pmease.gitplex.web.component.diff.revision.option.DiffOptionPanel;
import com.pmease.gitplex.web.page.repository.pullrequest.PullRequestChanged;
import com.pmease.gitplex.web.page.repository.pullrequest.requestdetail.RequestDetailPage;
import com.pmease.gitplex.web.page.repository.pullrequest.requestlist.RequestListPage;

import de.agilecoders.wicket.core.markup.html.bootstrap.components.TooltipConfig;
import de.agilecoders.wicket.core.markup.html.bootstrap.components.TooltipConfig.Placement;

@SuppressWarnings("serial")
public class RequestComparePage extends RequestDetailPage {

	private static final String TARGET_BRANCH_HEAD = "Target Branch Head";
	
	private static final String INTEGRATION_PREVIEW = "Integration Preview";

	private static final String PARAM_COMMENT = "comment";
	
	private static final String PARAM_OLD_COMMIT = "oldCommit";
	
	private static final String PARAM_NEW_COMMIT = "newCommit";
	
	private static final String PARAM_PATH = "path";
	
	private static final String PARAM_COMPARE_PATH = "comparePath";
	
	private HistoryState state = new HistoryState();
	
	private String oldCommitHash;
	
	private String newCommitHash;
	
	private String path;
	
	private String comparePath;
	
	private Long commentId;
	
	private final IModel<PullRequestComment> commentModel = new LoadableDetachableModel<PullRequestComment>() {

		@Override
		protected PullRequestComment load() {
			if (commentId != null)
				return GitPlex.getInstance(Dao.class).load(PullRequestComment.class, commentId);
			else
				return null;
		}
		
	};
	
	private WebMarkupContainer compareOptions;
	
	private DiffOptionPanel diffOption;
	
	private Component compareResult;
	
	private final IModel<Map<String, CommitDescription>> commitsModel = 
			new LoadableDetachableModel<Map<String, CommitDescription>>() {

		@Override
		protected LinkedHashMap<String, CommitDescription> load() {
			LinkedHashMap<String, CommitDescription> choices = new LinkedHashMap<>();
			PullRequest request = getPullRequest();

			String name = "Pull Request Base";
			CommitDescription description = new CommitDescription(name, request.getBaseCommit().getSubject());
			choices.put(request.getBaseCommitHash(), description);
			
			for (int i=0; i<request.getSortedUpdates().size(); i++) {
				PullRequestUpdate update = request.getSortedUpdates().get(i);
				Commit commit = update.getHeadCommit();
				int updateNo = i+1;
				if (i == request.getSortedUpdates().size()-1)
					name = "Latest Update Head";
				else
					name = "Head of Update " + updateNo;
				description = new CommitDescription(name, commit.getSubject());
				choices.put(commit.getHash(), description);
			}

			String targetHead = request.getTarget().getHead();
			if (!choices.containsKey(targetHead)) {
				description = new CommitDescription(TARGET_BRANCH_HEAD, 
						getRepository().getCommit(targetHead).getSubject());
				choices.put(targetHead, description);
			}

			if (request.isOpen()) {
				IntegrationPreview preview = request.getIntegrationPreview();
				if (preview != null && preview.getIntegrated() != null && !preview.getIntegrated().equals(preview.getRequestHead())) {
					Commit commit = getRepository().getCommit(preview.getIntegrated());
					choices.put(commit.getHash(), new CommitDescription(INTEGRATION_PREVIEW, commit.getSubject()));
				}
			}
			
			return choices;
		}
		
	};
	
	public RequestComparePage(PageParameters params) {
		super(params);

		state.commentId = params.get(PARAM_COMMENT).toOptionalLong();
		state.oldCommitHash = params.get(PARAM_OLD_COMMIT).toString();
		state.newCommitHash = params.get(PARAM_NEW_COMMIT).toString();
		state.path = params.get(PARAM_PATH).toString();
		state.comparePath = params.get(PARAM_COMPARE_PATH).toString();
		
		initWithState(state);
	}
	
	private void initWithState(HistoryState state) {
		commentId = state.commentId;
		PullRequestComment comment = commentModel.getObject();
		if (comment != null) {
			oldCommitHash = comment.getOldCommitHash();
			newCommitHash = comment.getNewCommitHash();
			path = comment.getBlobIdent().path;
			comparePath = comment.getCompareWith().path;
		} else {
			oldCommitHash = state.oldCommitHash;
			newCommitHash = state.newCommitHash;
			path = state.path;
			comparePath = state.comparePath;
			if (oldCommitHash == null)
				oldCommitHash = getPullRequest().getBaseCommitHash();
			if (newCommitHash == null)
				newCommitHash = getPullRequest().getLatestUpdate().getHeadCommitHash();
		}
	}
	
	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		compareOptions = new WebMarkupContainer("compareOptions");
		compareOptions.add(new StickyBehavior());
		
		add(compareOptions);

		WebMarkupContainer oldSelector = new WebMarkupContainer("oldSelector");
		compareOptions.add(oldSelector);
		oldSelector.add(new Label("label", new LoadableDetachableModel<String>() {

			@Override
			protected String load() {
				CommitDescription description = commitsModel.getObject().get(oldCommitHash);
				if (description != null)
					return GitUtils.abbreviateSHA(oldCommitHash) + " - " + description.getName();
				else
					return GitUtils.abbreviateSHA(oldCommitHash);
			}
			
		}).add(new TooltipBehavior(new LoadableDetachableModel<String>() {

			@Override
			protected String load() {
				CommitDescription description = commitsModel.getObject().get(oldCommitHash);
				if (description != null) 
					return description.getSubject();
				else 
					return getRepository().getCommit(oldCommitHash).getSubject();
			}
			
		}, new TooltipConfig().withPlacement(Placement.top))));
		
		DropdownPanel oldChoicesDropdown = new DropdownPanel("oldChoices", false) {

			@Override
			protected Component newContent(String id) {
				return new CommitChoicePanel(id) {

					@Override
					protected void onSelect(AjaxRequestTarget target, String commitHash) {
						oldCommitHash = commitHash;
						detachComment();
						hide(target);
						onStateChange(target);
					}
					
				};
			}
			
		}; 
		compareOptions.add(oldChoicesDropdown);
		oldSelector.add(new DropdownBehavior(oldChoicesDropdown).alignWithTrigger(0, 100, 0, 0));
		
		WebMarkupContainer newSelector = new WebMarkupContainer("newSelector");
		compareOptions.add(newSelector);
		newSelector.add(new Label("label", new LoadableDetachableModel<String>() {

			@Override
			protected String load() {
				CommitDescription description = commitsModel.getObject().get(newCommitHash);
				if (description != null)
					return GitUtils.abbreviateSHA(newCommitHash) + " - " + description.getName();
				else
					return GitUtils.abbreviateSHA(newCommitHash);
			}
			
		}).add(new TooltipBehavior(new LoadableDetachableModel<String>() {

			@Override
			protected String load() {
				CommitDescription description = commitsModel.getObject().get(newCommitHash);
				if (description != null)
					return description.getSubject();
				else
					return getRepository().getCommit(newCommitHash).getSubject();
			}
			
		}, new TooltipConfig().withPlacement(Placement.top))));
		
		DropdownPanel newChoicesDropdown = new DropdownPanel("newChoices", false) {

			@Override
			protected Component newContent(String id) {
				return new CommitChoicePanel(id) {

					@Override
					protected void onSelect(AjaxRequestTarget target, String commitHash) {
						newCommitHash = commitHash;
						detachComment();
						hide(target);
						onStateChange(target);
					}
					
				};
			}
			
		}; 
		compareOptions.add(newChoicesDropdown);
		newSelector.add(new DropdownBehavior(newChoicesDropdown).alignWithTrigger(0, 100, 0, 0));

		MenuPanel commonComparisons = new MenuPanel("comparisonChoices") {

			@Override
			protected List<MenuItem> getMenuItems() {
				List<MenuItem> items = new ArrayList<>();
				
				items.add(new ComparisonChoiceItem("Base", "Latest Update") {

					@Override
					protected void onSelect(AjaxRequestTarget target) {
						hide(target);
						
						oldCommitHash = getPullRequest().getBaseCommitHash();
						newCommitHash = getPullRequest().getLatestUpdate().getHeadCommitHash();
						detachComment();
						onStateChange(target);
					}

				});

				PullRequest request = getPullRequest();
				if (request.isOpen()) {
					final IntegrationPreview preview = request.getIntegrationPreview();
					if (preview != null && preview.getIntegrated() != null) {
						try (FileRepository jgitRepo = getRepository().openAsJGitRepo();) {
							List<DiffEntry> diffs = getRepository().getDiffs(
									preview.getRequestHead(), preview.getIntegrated(), false);
							if (!diffs.isEmpty()) {
								items.add(new ComparisonChoiceItem("Target Branch", "Integration Preview") {

									@Override
									protected void onSelect(AjaxRequestTarget target) {
										hide(target);
										
										oldCommitHash = getPullRequest().getTarget().getHead();
										newCommitHash = preview.getIntegrated();
										detachComment();
										onStateChange(target);
									}
									
								});
							}
						}
					}
				}

				for (int i=0; i<getPullRequest().getSortedUpdates().size(); i++) {
					PullRequestUpdate update = getPullRequest().getSortedUpdates().get(i);
					final String baseCommit = update.getBaseCommitHash();
					final String headCommit = update.getHeadCommitHash();
					int index = i+1;
					String oldLabel;
					if (index > 1) 
						oldLabel = "Update " + (index-1);
					else
						oldLabel = "Request Base";
					
					String newLabel;
					if (index == getPullRequest().getSortedUpdates().size())
						newLabel = "Latest Update";
					else
						newLabel = "Update " + index;
					items.add(new ComparisonChoiceItem(oldLabel, newLabel) {

						@Override
						protected void onSelect(AjaxRequestTarget target) {
							hide(target);

							oldCommitHash = baseCommit;
							newCommitHash = headCommit;
							detachComment();
							
							onStateChange(target);
						}
						
					});
				}
				
				return items;
			}
			
		};
		
		compareOptions.add(commonComparisons);
		compareOptions.add(new WebMarkupContainer("comparisonSelector")
				.add(new MenuBehavior(commonComparisons)
				.alignWithTrigger(50, 100, 50, 0)));
		
		compareOptions.add(diffOption = new DiffOptionPanel("diffOption", repoModel, new AbstractReadOnlyModel<String>() {

			@Override
			public String getObject() {
				return newCommitHash;
			}
			
		}) {

			@Override
			protected void onSelectPath(AjaxRequestTarget target, String path) {
				RequestComparePage.this.path = path;
				comparePath = null;
				if (commentId != null) {
					detachComment();
				} else {
					state.path = path;
					state.comparePath = null;
				}
				newCompareResult(target);
				pushState(target);
			}

			@Override
			protected void onLineProcessorChange(AjaxRequestTarget target) {
				newCompareResult(target);
			}

			@Override
			protected void onDiffModeChange(AjaxRequestTarget target) {
				newCompareResult(target);
			}

			@Override
			protected Component getDirtyContainer() {
				return compareResult;
			}
			
		});
		
		add(new WebMarkupContainer("outdatedAlert") {

			@Override
			public void onEvent(final IEvent<?> event) {
				super.onEvent(event);

				if (event.getPayload() instanceof PullRequestChanged) {
					if (state.newCommitHash == null) {
						setVisible(true);
						PullRequestChanged pullRequestChanged = (PullRequestChanged) event.getPayload();
						pullRequestChanged.getTarget().add(this);
					}
					
				}
			}

			@Override
			protected void onInitialize() {
				super.onInitialize();
				
				setVisible(false);
				setOutputMarkupPlaceholderTag(true);
			}

		});
		
		newCompareResult(null);
	}

	private void detachComment() {
		commentId = null;
		state.oldCommitHash = oldCommitHash;
		state.newCommitHash = newCommitHash;
		state.path = path;
		state.comparePath = comparePath;
		state.commentId = null;
	}
	
	@Override
	public void onDetach() {
		commentModel.detach();
		commitsModel.detach();
		
		super.onDetach();
	}
	
	public static PageParameters paramsOf(PullRequestComment comment) {
		PageParameters params = RequestDetailPage.paramsOf(comment.getRequest());
		params.set(PARAM_COMMENT, comment.getId());
		
		return params;
	}

	public static PageParameters paramsOf(PullRequest request, @Nullable String oldCommitHash, 
			@Nullable String newCommitHash) {
		return paramsOf(request, oldCommitHash, newCommitHash, null);
	}
	
	public static PageParameters paramsOf(PullRequest request, @Nullable String oldCommitHash, 
			@Nullable String newCommitHash, @Nullable String path) {
		return paramsOf(request, oldCommitHash, newCommitHash, path, null, null);
	}
	
	public static PageParameters paramsOf(PullRequest request, @Nullable String oldCommitHash, 
			@Nullable String newCommitHash, @Nullable String path, @Nullable String comparePath, 
			@Nullable Long commentId) {
		PageParameters params = RequestDetailPage.paramsOf(request);

		if (oldCommitHash != null)
			params.set(PARAM_OLD_COMMIT, oldCommitHash);
		if (newCommitHash != null)
			params.set(PARAM_NEW_COMMIT, newCommitHash);
		if (path != null)
			params.set(PARAM_PATH,  path);
		if (comparePath != null)
			params.set(PARAM_COMPARE_PATH,  path);
		if (commentId != null)
			params.set(PARAM_COMMENT, commentId);
		return params;
	}
	
	@Override
	public void onEvent(IEvent<?> event) {
		super.onEvent(event);
		
		if (event.getPayload() instanceof CommentRemoved) {
			CommentRemoved commentRemoved = (CommentRemoved) event.getPayload();
			PullRequestComment comment = (PullRequestComment) commentRemoved.getComment();
			
			// compare identifier instead of comment object as comment may have been deleted
			// to cause LazyInitializationException
			if (HibernateUtils.getId(comment).equals(commentId)) {
				detachComment();
				onStateChange(commentRemoved.getTarget());
			}
		}
	}

	private static class CommitDescription implements Serializable {
		private final String name;
		
		private final String subject;
		
		CommitDescription(final String name, final String subject) {
			this.name = name;
			this.subject = subject;
		}

		public String getName() {
			return name;
		}

		public String getSubject() {
			return subject;
		}
		
	}
	
	private abstract class CommitChoicePanel extends Fragment {

		CommitChoicePanel(String id) {
			super(id, "commitChoiceFrag", RequestComparePage.this);
		}

		protected abstract void onSelect(AjaxRequestTarget target, String commitHash);
		
		@Override
		protected void onInitialize() {
			super.onInitialize();
			
			setOutputMarkupId(true);
			
			IModel<List<Map.Entry<String, CommitDescription>>> model = 
					new LoadableDetachableModel<List<Map.Entry<String, CommitDescription>>>() {

				@Override
				protected List<Entry<String, CommitDescription>> load() {
					List<Entry<String, CommitDescription>> entries = new ArrayList<>();
					entries.addAll(commitsModel.getObject().entrySet());
					return entries;
				}
				
			};
			
			add(new ListView<Map.Entry<String, CommitDescription>>("commits", model) {

				@Override
				protected void populateItem(final ListItem<Entry<String, CommitDescription>> item) {
					AjaxLink<Void> link = new AjaxLink<Void>("commit") {

						@Override
						protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
							super.updateAjaxAttributes(attributes);
							attributes.getAjaxCallListeners().add(new IndicateLoadingListener());
							attributes.getAjaxCallListeners().add(new ConfirmLeaveListener(compareResult));
						}

						@Override
						public void onClick(AjaxRequestTarget target) {
							Map.Entry<String, CommitDescription> entry = item.getModelObject();
							onSelect(target, entry.getKey());
						}
						
					};
					Map.Entry<String, CommitDescription> entry = item.getModelObject();
					String hash = GitUtils.abbreviateSHA(entry.getKey(), 7);
					String name = entry.getValue().getName();
					link.add(new Label("commit", hash));
					link.add(new Label("name", name).setVisible(name != null));
					if (entry.getValue().getSubject() != null)
						link.add(new Label("subject", entry.getValue().getSubject()));
					else
						link.add(new WebMarkupContainer("subject").setVisible(false));
					item.add(link);
				}
				
			});
		}

	}
	
	private abstract class ComparisonChoiceItem extends MenuItem {

		private final String oldName;
		
		private final String newName;
		
		ComparisonChoiceItem(String oldName, String newName) {
			this.oldName = oldName;
			this.newName = newName;
		}

		protected abstract void onSelect(AjaxRequestTarget target);

		@Override
		public Component newContent(String componentId) {
			Fragment fragment = new Fragment(componentId, "comparisonChoiceFrag", RequestComparePage.this);
			AjaxLink<Void> link = new AjaxLink<Void>("link") {

				@Override
				protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
					super.updateAjaxAttributes(attributes);
					attributes.getAjaxCallListeners().add(new IndicateLoadingListener());
					attributes.getAjaxCallListeners().add(new ConfirmLeaveListener(compareResult));
				}

				@Override
				public void onClick(AjaxRequestTarget target) {
					onSelect(target);
				}
				
			};
			fragment.add(link);
			
			link.add(new Label("old", oldName));
			link.add(new Label("new", newName));
			
			return fragment;
		}
	}

	@Override
	protected void onPopState(AjaxRequestTarget target, Serializable data) {
		super.onPopState(target, data);

		HistoryState state = (HistoryState) data;
		initWithState(state);
		
		target.add(compareOptions);
		newCompareResult(target);
	}
	
	@Override
	protected void onSelect(AjaxRequestTarget target, Repository repository) {
		setResponsePage(RequestListPage.class, paramsOf(repository));
	}

	private void pushState(AjaxRequestTarget target) {
		PageParameters params = paramsOf(getPullRequest(), state.oldCommitHash, state.newCommitHash, 
				state.path, state.comparePath, state.commentId);
		CharSequence url = RequestCycle.get().urlFor(RequestComparePage.class, params);
		pushState(target, url.toString(), state);
	}
	
	private void onStateChange(AjaxRequestTarget target) {
		pushState(target);
		
		target.add(compareOptions);
		newCompareResult(target);
	}
	
	private void newCompareResult(@Nullable AjaxRequestTarget target) {
		InlineCommentSupport commentSupport;
		
		List<String> commentables = getPullRequest().getCommentables();
		int oldCommitIndex = commentables.indexOf(oldCommitHash);
		int newCommitIndex = commentables.indexOf(newCommitHash);
		if (oldCommitIndex == -1 || newCommitIndex == -1 || oldCommitIndex > newCommitIndex) {
			commentSupport = null;
		} else {
			commentSupport = new InlineCommentSupport() {
				
				@Override
				public Map<Integer, List<InlineComment>> getComments(BlobIdent blobIdent) {
					Map<Integer, List<InlineComment>> comments = new HashMap<>();
					for (PullRequestComment comment: getPullRequest().getComments()) {
						if (comment.getInlineInfo() != null && comment.getBlobIdent().equals(blobIdent)) {
							List<InlineComment> commentsAtLine = comments.get(comment.getLine());
							if (commentsAtLine == null) {
								commentsAtLine = new ArrayList<>();
								comments.put(comment.getLine(), commentsAtLine);
							}
							commentsAtLine.add(comment);
						}
					}
					return comments;
				}
				
				@Override
				public InlineComment getConcernedComment() {
					return commentModel.getObject();
				}
				
				@Override
				public InlineComment addComment(BlobIdent blobInfo, BlobIdent compareWith, 
						int line, String content) {
					User user = GitPlex.getInstance(UserManager.class).getCurrent();
					Preconditions.checkNotNull(user);
					PullRequestComment comment = new PullRequestComment();
					getPullRequest().getComments().add(comment);
					comment.setUser(user);
					comment.setDate(new Date());
					comment.setContent(content);
					comment.setRequest(getPullRequest());
					comment.setBlobIdent(blobInfo);
					comment.setCompareWith(compareWith);
					comment.setLine(line);
					InheritableThreadLocalData.set(new WebSocketRenderBehavior.PageId(getPageId()));
					try {
						GitPlex.getInstance(PullRequestCommentManager.class).save(comment, true);
					} finally {
						InheritableThreadLocalData.clear();
					}
					return comment;
				}

				@Override
				public InlineComment loadComment(Long commentId) {
					return GitPlex.getInstance(Dao.class).load(PullRequestComment.class, commentId);
				}
			};
		};
		
		compareResult = new RevisionDiffPanel("compareResult", 
				repoModel, oldCommitHash, newCommitHash, path, comparePath, 
				diffOption.getLineProcessor(), diffOption.getDiffMode(), 
				commentSupport) {

			@Override
			protected void onClearPath(AjaxRequestTarget target) {
				path = null;
				comparePath = null;
				if (commentId != null) {
					detachComment();
				} else {
					state.path = null;
					state.comparePath = null;
				}
				newCompareResult(target);
				pushState(target);
			}
			
		};
		compareResult.setOutputMarkupId(true);
		if (target != null) {
			replace(compareResult);
			target.add(compareResult);
		} else {
			add(compareResult);
		}
	}
	
	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(CssHeaderItem.forReference(
				new CssResourceReference(RequestComparePage.class, "request-compare.css")));
	}

}