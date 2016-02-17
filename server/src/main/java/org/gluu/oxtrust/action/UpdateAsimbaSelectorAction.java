/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxtrust.action;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import javax.faces.context.FacesContext;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import org.gluu.asimba.util.ldap.selector.ApplicationSelectorEntry;
import org.gluu.oxtrust.ldap.service.AsimbaService;
import org.gluu.oxtrust.ldap.service.SvnSyncTimer;
import org.gluu.oxtrust.util.OxTrustConstants;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.Create;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Logger;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.annotations.security.Restrict;
import org.jboss.seam.core.ResourceLoader;
import org.jboss.seam.faces.FacesMessages;
import org.jboss.seam.log.Log;
import org.jboss.seam.security.Identity;
import org.xdi.config.oxtrust.ApplicationConfiguration;


/**
 * Action class for updating and adding the Requestor->IDP Selector to Asimba
 * 
 * @author Dmitry Ognyannikov
 */
@Scope(ScopeType.CONVERSATION)
@Name("updateAsimbaSelectorAction")
@Restrict("#{identity.loggedIn}")
public class UpdateAsimbaSelectorAction implements Serializable {

    private static final long serialVersionUID = -1242167044333943680L;
    
    @Logger
    private Log log;

    @In(value = "#{oxTrustConfiguration.applicationConfiguration}")
    private ApplicationConfiguration applicationConfiguration;

    @In
    private Identity identity;

    @In
    private SvnSyncTimer svnSyncTimer;
    
    @In
    private FacesMessages facesMessages;

    @In(value = "#{facesContext}")
    private FacesContext facesContext;
    
    @In
    private ResourceLoader resourceLoader;
    
    @In
    private AsimbaService asimbaService;
    
    private ApplicationSelectorEntry selector = new ApplicationSelectorEntry();
    
    private List<ApplicationSelectorEntry> selectorList = new ArrayList<ApplicationSelectorEntry>();
    
    @NotNull
    @Size(min = 0, max = 30, message = "Length of search string should be less than 30")
    private String searchPattern = "";
    
    public UpdateAsimbaSelectorAction() {
        
    }
    
    @Create
    public void init() {
        log.info("init() Selector call");
        
        selector = new ApplicationSelectorEntry();
        
        refresh();
    }
    
    public void refresh() {
        log.info("refresh() Selector call");
        // list loading
        selectorList = asimbaService.loadSelectors();
    }
    
    @Restrict("#{s:hasPermission('trust', 'access')}")
    public String add() {
        log.info("add() Selector", selector);
        synchronized (svnSyncTimer) {
            asimbaService.addApplicationSelectorEntry(selector);
        }
        selector = new ApplicationSelectorEntry();
        return OxTrustConstants.RESULT_SUCCESS;
    }
    
    @Restrict("#{s:hasPermission('trust', 'access')}")
    public String update() {
        log.info("update() Selector", selector);
        synchronized (svnSyncTimer) {
            asimbaService.updateApplicationSelectorEntry(selector);
        }
        selector = new ApplicationSelectorEntry();
        return OxTrustConstants.RESULT_SUCCESS;
    }
    
    @Restrict("#{s:hasPermission('trust', 'access')}")
    public void cancel() {
        log.info("cancel() Selector", selector);
        selector = new ApplicationSelectorEntry();
    }

    @Restrict("#{s:hasPermission('trust', 'access')}")
    public String save() {
        log.info("save() Selector", selector);
        synchronized (svnSyncTimer) {
            asimbaService.addApplicationSelectorEntry(selector);
        }
        selector = new ApplicationSelectorEntry();
        return OxTrustConstants.RESULT_SUCCESS;
    }
    
    @Restrict("#{s:hasPermission('trust', 'access')}")
    public String delete() {
        log.info("delete() Selector", selector);
        synchronized (svnSyncTimer) {
            //TODO: delete current node
        }
        selector = new ApplicationSelectorEntry();
        return OxTrustConstants.RESULT_SUCCESS;
    }
    
    @Restrict("#{s:hasPermission('person', 'access')}")
    public String search() {
        log.info("search() Selector searchPattern:", searchPattern);
        synchronized (svnSyncTimer) {
            if (searchPattern != null && !"".equals(searchPattern)){
                try {
                    selectorList = asimbaService.searchSelectors(searchPattern, 0);
                } catch (Exception ex) {
                    log.error("LDAP search exception", ex);
                }
            } else {
                //list loading
                selectorList = asimbaService.loadSelectors();
            }
        }
        return OxTrustConstants.RESULT_SUCCESS;
    }

    /**
     * @return the selector
     */
    public ApplicationSelectorEntry getSelector() {
        return selector;
    }

    /**
     * @param selector the selector to set
     */
    public void setSelector(ApplicationSelectorEntry selector) {
        this.selector = selector;
    }

    /**
     * @return the selectorList
     */
    public List<ApplicationSelectorEntry> getSelectorList() {
        return selectorList;
    }

    /**
     * @param selectorList the selectorList to set
     */
    public void setSelectorList(List<ApplicationSelectorEntry> selectorList) {
        this.selectorList = selectorList;
    }

    /**
     * @return the searchPattern
     */
    public String getSearchPattern() {
        return searchPattern;
    }

    /**
     * @param searchPattern the searchPattern to set
     */
    public void setSearchPattern(String searchPattern) {
        this.searchPattern = searchPattern;
    }
}
