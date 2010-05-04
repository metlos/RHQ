/*
 * RHQ Management Platform
 * Copyright (C) 2005-2010 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.gui.coregui.client.bundle.create;

import java.net.URL;
import java.util.LinkedHashMap;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.smartgwt.client.widgets.Canvas;
import com.smartgwt.client.widgets.form.DynamicForm;
import com.smartgwt.client.widgets.form.fields.CanvasItem;
import com.smartgwt.client.widgets.form.fields.LinkItem;
import com.smartgwt.client.widgets.form.fields.TextAreaItem;
import com.smartgwt.client.widgets.form.fields.TextItem;
import com.smartgwt.client.widgets.form.fields.events.ClickEvent;
import com.smartgwt.client.widgets.form.fields.events.ClickHandler;

import org.rhq.core.domain.bundle.BundleVersion;
import org.rhq.core.domain.criteria.BundleVersionCriteria;
import org.rhq.core.domain.util.PageList;
import org.rhq.enterprise.gui.coregui.client.CoreGUI;
import org.rhq.enterprise.gui.coregui.client.components.form.RadioGroupWithComponentsItem;
import org.rhq.enterprise.gui.coregui.client.components.upload.BundleDistributionFileUploadForm;
import org.rhq.enterprise.gui.coregui.client.components.upload.DynamicCallbackForm;
import org.rhq.enterprise.gui.coregui.client.components.upload.DynamicFormHandler;
import org.rhq.enterprise.gui.coregui.client.components.upload.DynamicFormSubmitCompleteEvent;
import org.rhq.enterprise.gui.coregui.client.components.upload.TextFileRetrieverForm;
import org.rhq.enterprise.gui.coregui.client.components.wizard.WizardStep;
import org.rhq.enterprise.gui.coregui.client.gwt.BundleGWTServiceAsync;
import org.rhq.enterprise.gui.coregui.client.gwt.GWTServiceLookup;
import org.rhq.enterprise.gui.coregui.client.util.message.Message;
import org.rhq.enterprise.gui.coregui.client.util.message.Message.Severity;

public class BundleUploadDistroFileStep implements WizardStep {

    private final AbstractBundleCreateWizard wizard;
    private final BundleGWTServiceAsync bundleServer = GWTServiceLookup.getBundleService();

    private BundleDistributionFileUploadForm distroUploadForm;
    private DynamicForm mainCanvasForm;
    private TextItem urlTextItem;
    private BundleDistributionFileUploadForm uploadDistroForm;

    public BundleUploadDistroFileStep(AbstractBundleCreateWizard bundleCreationWizard) {
        this.wizard = bundleCreationWizard;
    }

    public Canvas getCanvas() {
        if (mainCanvasForm == null) {
            LinkedHashMap<String, DynamicForm> radioItems = new LinkedHashMap<String, DynamicForm>();
            radioItems.put("URL", createUrlForm());
            radioItems.put("Upload", createUploadForm());
            radioItems.put("Recipe", createRecipeForm());

            mainCanvasForm = new DynamicForm();
            RadioGroupWithComponentsItem radioGroup = new RadioGroupWithComponentsItem("bundleDistroRadioGroup",
                "Bundle Distribution", radioItems, mainCanvasForm);
            radioGroup.setShowTitle(false);
            mainCanvasForm.setItems(radioGroup);
        }
        return mainCanvasForm;
    }

    public boolean nextPage() {
        return true;
    }

    public String getName() {
        return "Provide A Bundle Distribution";
    }

    private DynamicForm createUrlForm() {
        urlTextItem = new TextItem("url", "URL");
        urlTextItem.setRequired(false);
        urlTextItem.setShowTitle(false);
        DynamicForm urlForm = new DynamicForm();
        urlForm.setPadding(20);
        urlForm.setWidth100();
        urlForm.setItems(urlTextItem);
        return urlForm;
    }

    private BundleDistributionFileUploadForm createUploadForm() {
        uploadDistroForm = new BundleDistributionFileUploadForm(false);
        uploadDistroForm.setPadding(20);
        uploadDistroForm.setWidth100();
        return uploadDistroForm;
    }

    private DynamicForm createRecipeForm() {
        final DynamicCallbackForm form = new DynamicCallbackForm("recipeForm");
        form.setWidth100();
        form.setMargin(Integer.valueOf(20));
        form.setShowInlineErrors(false);

        final LinkItem showUpload = new LinkItem("recipeUploadLink");
        showUpload.setValue("Click To Upload A Recipe File");
        showUpload.setShowTitle(false);

        final CanvasItem upload = new CanvasItem("recipeUploadCanvas");
        upload.setShowTitle(false);
        upload.setVisible(false);

        final TextFileRetrieverForm textFileRetrieverForm = new TextFileRetrieverForm();
        upload.setCanvas(textFileRetrieverForm);

        showUpload.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent clickEvent) {
                form.hideItem(showUpload.getName());
                form.showItem(upload.getName());
            }
        });

        final TextAreaItem recipe = new TextAreaItem("recipeText");
        recipe.setShowTitle(false);
        recipe.setRequired(false);
        recipe.setColSpan(4);
        recipe.setWidth("*");
        recipe.setHeight(150);

        textFileRetrieverForm.addFormHandler(new DynamicFormHandler() {
            public void onSubmitComplete(DynamicFormSubmitCompleteEvent event) {
                wizard.setRecipe(event.getResults());
                recipe.setValue(event.getResults());
                textFileRetrieverForm.retrievalStatus(true);
                form.showItem(showUpload.getName());
                form.hideItem(upload.getName());
            }
        });

        form.setItems(showUpload, upload, recipe);

        return form;
    }

    private void processUrl() {
        String urlString = (String) this.urlTextItem.getValue();
        URL url;
        try {
            url = new URL(urlString);
        } catch (Exception e) {
            CoreGUI.getErrorHandler().handleError("Invalid URL [" + urlString + "]", e);
            wizard.setBundleVersion(null);
            setButtonsDisableMode(false);
            return;
        }

        BundleGWTServiceAsync bundleServer = GWTServiceLookup.getBundleService();
        bundleServer.createBundleVersionViaURL(url, new AsyncCallback<BundleVersion>() {
            public void onSuccess(BundleVersion result) {
                CoreGUI.getMessageCenter().notify(
                    new Message("Created bundle [" + result.getName() + "] version [" + result.getVersion() + "]",
                        Message.Severity.Info));
                wizard.setBundleVersion(result);
                setButtonsDisableMode(false);
                wizard.getView().incrementStep(); // go to the next step
            }

            public void onFailure(Throwable caught) {
                CoreGUI.getErrorHandler().handleError("Failed to create bundle", caught);
                wizard.setBundleVersion(null);
                setButtonsDisableMode(false);
            }
        });
    }

    private void processUpload() {
        if (Boolean.TRUE.equals(uploadDistroForm.getUploadResults())) {
            int bvId = uploadDistroForm.getBundleVersionId();
            BundleVersionCriteria criteria = new BundleVersionCriteria();
            criteria.addFilterId(bvId);
            BundleGWTServiceAsync bundleServer = GWTServiceLookup.getBundleService();
            bundleServer.findBundleVersionsByCriteria(criteria, new AsyncCallback<PageList<BundleVersion>>() {
                public void onSuccess(PageList<BundleVersion> result) {
                    BundleVersion bv = result.get(0);
                    CoreGUI.getMessageCenter().notify(
                        new Message("Created bundle [" + bv.getName() + "] version [" + bv.getVersion() + "]",
                            Message.Severity.Info));
                    wizard.setBundleVersion(bv);
                    setButtonsDisableMode(false);
                    wizard.getView().incrementStep(); // go to the next step
                }

                public void onFailure(Throwable caught) {
                    CoreGUI.getErrorHandler().handleError("Failed to create bundle", caught);
                    wizard.setBundleVersion(null);
                    setButtonsDisableMode(false);
                }
            });
        } else {
            CoreGUI.getMessageCenter().notify(new Message("Failed to upload bundle distribution file", Severity.Error));
            wizard.setBundleVersion(null);
            setButtonsDisableMode(false);
        }
    }

    private void processRecipe() {
        BundleGWTServiceAsync bundleServer = GWTServiceLookup.getBundleService();
        bundleServer.createBundleVersionViaRecipe(this.wizard.getRecipe(), new AsyncCallback<BundleVersion>() {
            public void onSuccess(BundleVersion result) {
                CoreGUI.getMessageCenter().notify(
                    new Message("Created bundle [" + result.getName() + "] version [" + result.getVersion() + "]",
                        Message.Severity.Info));
                wizard.setBundleVersion(result);
                setButtonsDisableMode(false);
                wizard.getView().incrementStep(); // go to the next step
            }

            public void onFailure(Throwable caught) {
                CoreGUI.getErrorHandler().handleError("Failed to create bundle", caught);
                wizard.setBundleVersion(null);
                wizard.setRecipe("");
                setButtonsDisableMode(false);
            }
        });
    }

    private void setButtonsDisableMode(boolean disabled) {
        wizard.getView().getCancelButton().setDisabled(disabled);
        wizard.getView().getNextButton().setDisabled(disabled);
        wizard.getView().getPreviousButton().setDisabled(disabled);
    }
}
