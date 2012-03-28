/*
 *
 *  * RHQ Management Platform
 *  * Copyright (C) 2005-2012 Red Hat, Inc.
 *  * All rights reserved.
 *  *
 *  * This program is free software; you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation version 2 of the License.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program; if not, write to the Free Software
 *  * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *
 */

package org.rhq.enterprise.gui.coregui.client.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.smartgwt.client.types.ListGridEditEvent;
import com.smartgwt.client.types.ListGridFieldType;
import com.smartgwt.client.widgets.events.DoubleClickEvent;
import com.smartgwt.client.widgets.events.DoubleClickHandler;
import com.smartgwt.client.widgets.form.fields.CheckboxItem;
import com.smartgwt.client.widgets.form.fields.events.ChangedEvent;
import com.smartgwt.client.widgets.form.fields.events.ChangedHandler;
import com.smartgwt.client.widgets.grid.CellFormatter;
import com.smartgwt.client.widgets.grid.HoverCustomizer;
import com.smartgwt.client.widgets.grid.ListGrid;
import com.smartgwt.client.widgets.grid.ListGridField;
import com.smartgwt.client.widgets.grid.ListGridRecord;

import org.rhq.core.domain.resource.ResourceCategory;
import org.rhq.enterprise.gui.coregui.client.CoreGUI;
import org.rhq.enterprise.gui.coregui.client.ImageManager;
import org.rhq.enterprise.gui.coregui.client.components.ExportModalWindow;
import org.rhq.enterprise.gui.coregui.client.components.table.Table;
import org.rhq.enterprise.gui.coregui.client.components.table.TableAction;

/**
* @author jsanda
*/
public class InventorySummaryReportTable extends Table<InventorySummaryDataSource> {

    private boolean exportAll;

    private ExportChangeHandler exportChangeHandler;

    public InventorySummaryReportTable(String locatorId) {
        super(locatorId);
        setDataSource(new InventorySummaryDataSource());
    }

    @Override
    protected void configureTable() {
        exportChangeHandler = new ExportChangeHandler(getListGrid(), InventorySummaryDataSource.Field.TYPEID,
            InventorySummaryDataSource.Field.EXPORT);

        List<ListGridField> fields = createListGridFields();

        setListGridDoubleClickHandler(new DoubleClickHandler() {
            @Override
            public void onDoubleClick(DoubleClickEvent event) {
                ListGrid lg = (ListGrid) event.getSource();
                ListGridRecord selected = lg.getSelectedRecord();
                String url = getResourceTypeTableUrl(selected);
                if (url != null) {
                    CoreGUI.goToView(url);
                }
            }
        });

        setListGridFields(fields.toArray(new ListGridField[fields.size()]));
        getListGrid().setEditEvent(ListGridEditEvent.CLICK);
        getListGrid().setEditByCell(true);
        addExportAction();
    }

    protected List<ListGridField> createListGridFields() {
        List<ListGridField> fields = new ArrayList<ListGridField>();
        fields.add(createNameField());
        fields.add(createPluginField());
        fields.add(createCategoryField());
        fields.add(createVersionField());
        fields.add(createCountField());
        fields.add(createExportField());

        // TODO (ips, 11/11/11): The groupBy functionality is very buggy in SmartGWT 2.4. Once they fix it
        //                       uncomment these lines to allow grouping by the plugin or category fields.
        /*getListGrid().setCanGroupBy(true);
        fieldTypeName.setCanGroupBy(false);
        fieldVersion.setCanGroupBy(false);
        fieldCount.setCanGroupBy(false); */

        return fields;
    }

    protected ListGridField createNameField() {
        ListGridField field = new ListGridField(InventorySummaryDataSource.Field.TYPENAME,
            MSG.common_title_resource_type());
        field.setWidth("35%");

        field.setCellFormatter(new CellFormatter() {
            @Override
            public String format(Object value, ListGridRecord record, int rowNum, int colNum) {
                String url = getResourceTypeTableUrl(record);
                if (url == null) {
                    return value.toString();
                }

                return "<a href=\"" + url + "\">" + value.toString() + "</a>";
            }
        });

        return field;
    }

    protected ListGridField createPluginField() {
        ListGridField field = new ListGridField(InventorySummaryDataSource.Field.TYPEPLUGIN, MSG.common_title_plugin());
        field.setWidth("10%");
        return field;
    }

    protected ListGridField createCategoryField() {
        ListGridField field = new ListGridField(InventorySummaryDataSource.Field.CATEGORY,
            MSG.common_title_category());

        field.setWidth(70);
        field.setType(ListGridFieldType.ICON);
        field.setShowValueIconOnly(true);
        HashMap<String, String> categoryIcons = new HashMap<String, String>(3);
        categoryIcons
            .put(ResourceCategory.PLATFORM.name(), ImageManager.getResourceIcon(ResourceCategory.PLATFORM));
        categoryIcons.put(ResourceCategory.SERVER.name(), ImageManager.getResourceIcon(ResourceCategory.SERVER));
        categoryIcons.put(ResourceCategory.SERVICE.name(), ImageManager.getResourceIcon(ResourceCategory.SERVICE));
        field.setValueIcons(categoryIcons);
        field.setShowHover(true);
        field.setHoverCustomizer(new HoverCustomizer() {
            @Override
            public String hoverHTML(Object value, ListGridRecord record, int rowNum, int colNum) {
                String cat = record.getAttribute(InventorySummaryDataSource.Field.CATEGORY);
                if (ResourceCategory.PLATFORM.name().equals(cat)) {
                    return MSG.common_title_platform();
                } else if (ResourceCategory.SERVER.name().equals(cat)) {
                    return MSG.common_title_server();
                } else if (ResourceCategory.SERVICE.name().equals(cat)) {
                    return MSG.common_title_service();
                }
                return "";
            }
        });

        return field;
    }

    protected ListGridField createVersionField() {
        ListGridField field = new ListGridField(InventorySummaryDataSource.Field.VERSION, MSG.common_title_version());
        field.setWidth("*");
        return field;
    }

    protected ListGridField createCountField() {
        ListGridField field = new ListGridField(InventorySummaryDataSource.Field.COUNT, MSG.common_title_count());
        field.setWidth(60);
        return field;
    }

    protected ListGridField createExportField() {
        ListGridField field = new ListGridField("exportDetails", "Export Details");

        field.setCanToggle(true);
        field.setCanEdit(true);
        field.addChangedHandler(exportChangeHandler);

        return field;
    }

    private void addExportAction() {
        addTableAction("Export", "Export", new TableAction() {
            @Override
            public boolean isEnabled(ListGridRecord[] selection) {
                return true;
            }

            @Override
            public void executeAction(ListGridRecord[] selection, Object actionValue) {
                ExportModalWindow exportModalWindow = ExportModalWindow.createExportWindowForInventorySummary(
                    getReportNameForDownloadURL(), exportAll, exportChangeHandler.getResourceTypeIds());
                exportModalWindow.show();
                refreshTableInfo();
            }
        });
    }

    @Override
    protected void configureTableFilters() {
        CheckboxItem exportAllDetails = new CheckboxItem("exportAllDetails", "Export All Details");
        exportAllDetails.setLabelAsTitle(true);

        setShowFilterForm(true);
        setFilterFormItems(exportAllDetails);

        exportAllDetails.addChangedHandler(new ChangedHandler() {
            @Override
            public void onChanged(ChangedEvent event) {
                exportAll = !exportAll;
                ListGrid table = getListGrid();
                int numColumns = table.getFields().length;
                int row = 0;
                for (ListGridRecord record : table.getRecords()) {
                    record.setAttribute(InventorySummaryDataSource.Field.EXPORT, exportAll);
                    table.refreshCell(row++, numColumns - 1);
                }
            }
        });
    }

    private String getResourceTypeTableUrl(ListGridRecord selected) {
        String url = null;
        if (selected != null) {
            int resourceTypeId = selected.getAttributeAsInt(InventorySummaryDataSource.Field.TYPEID);
            String version = selected.getAttribute(InventorySummaryDataSource.Field.VERSION);
            if (version == null) {
                url = "#Reports/Inventory/" + getReportNameForResourceTypeURL() + "/" + resourceTypeId;
            } else {
                url = "#Reports/Inventory/" + getReportNameForResourceTypeURL() + "/" + resourceTypeId + "/" + version;
            }
        }
        return url;
    }

    protected String getReportNameForResourceTypeURL() {
        return "InventorySummary";
    }

    protected String getReportNameForDownloadURL() {
        return "inventorySummary";
    }

}
