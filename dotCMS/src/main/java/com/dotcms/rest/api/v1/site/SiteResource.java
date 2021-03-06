package com.dotcms.rest.api.v1.site;

import static com.dotcms.util.CollectionsUtils.map;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import com.dotcms.repackage.com.google.common.annotations.VisibleForTesting;
import com.dotcms.repackage.javax.ws.rs.GET;
import com.dotcms.repackage.javax.ws.rs.PUT;
import com.dotcms.repackage.javax.ws.rs.Path;
import com.dotcms.repackage.javax.ws.rs.PathParam;
import com.dotcms.repackage.javax.ws.rs.Produces;
import com.dotcms.repackage.javax.ws.rs.QueryParam;
import com.dotcms.repackage.javax.ws.rs.core.Context;
import com.dotcms.repackage.javax.ws.rs.core.MediaType;
import com.dotcms.repackage.javax.ws.rs.core.Response;
import com.dotcms.repackage.org.apache.commons.lang.StringUtils;
import com.dotcms.repackage.org.glassfish.jersey.server.JSONP;
import com.dotcms.rest.InitDataObject;
import com.dotcms.rest.ResponseEntityView;
import com.dotcms.rest.WebResource;
import com.dotcms.rest.annotation.NoCache;
import com.dotcms.rest.exception.mapper.ExceptionMapperUtil;
import com.dotcms.util.I18NUtil;
import com.dotcms.util.PaginationUtil;
import com.dotmarketing.beans.Host;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.UserAPI;
import com.dotmarketing.util.PaginatedArrayList;
import com.dotmarketing.util.UtilMethods;
import com.dotmarketing.util.WebKeys;
import com.liferay.portal.model.User;
import com.liferay.util.LocaleUtil;

/**
 * This resource provides all the different end-points associated to information
 * and actions that the front-end can perform on the Site Browser page.
 * 
 * @author jsanca
 */
@Path("/v1/site")
public class SiteResource implements Serializable {

	private static final long serialVersionUID = 1L;

	private static final String NO_FILTER = "*";

	private final UserAPI userAPI;
    private final WebResource webResource;
    private final SiteHelper siteHelper;
    private final I18NUtil i18NUtil;

    public SiteResource() {
        this(new WebResource(),
                SiteHelper.getInstance(),
                I18NUtil.INSTANCE, APILocator.getUserAPI());
    }

    @VisibleForTesting
    public SiteResource(final WebResource webResource,
                               final SiteHelper siteHelper,
                               final I18NUtil i18NUtil, final UserAPI userAPI) {
        this.webResource = webResource;
        this.siteHelper  = siteHelper;
        this.i18NUtil    = i18NUtil;
        this.userAPI = userAPI;
    }

	/**
	 * Returns the list of Sites that the currently logged-in user has access
	 * to. In the front-end, this list is displayed in the Site Selector
	 * component. Its contents will also be refreshed when performing the "Login
	 * As".
	 * <p>
	 * The site that will be selected in the UI component will be retrieved from
	 * the HTTP session. If such a site does not exist in the list of sites, the
	 * first site in it will be selected.
	 * 
	 * @param req
	 *            - The {@link HttpServletRequest} object.
	 * @return The {@link Response} containing the list of Sites.
	 */
    @GET
    @Path ("/currentSite")
    @JSONP
    @NoCache
    @Produces({MediaType.APPLICATION_JSON, "application/javascript"})
    public final Response currentSite(@Context final HttpServletRequest req) {
        final Map<String,Object> userSites;
        Response response = null;
        final InitDataObject initData = this.webResource.init(null, true, req, true, null);
        final User user = initData.getUser();
        final HttpSession session = req.getSession();
        try {
			
        	userSites = siteHelper.getPaginatedOrderedSites(Boolean.FALSE, user, StringUtils.EMPTY, 1, 1, Boolean.FALSE);
            		                    
			final String currentSite = this.siteHelper.getSelectedSite((List<Host>)userSites.get(siteHelper.RESULTS),
					(String) session.getAttribute(WebKeys.CMS_SELECTED_HOST_ID), user);
            response = Response.ok( new ResponseEntityView( map("sites", userSites.get(siteHelper.RESULTS),"sitesCounter", userSites.get(siteHelper.TOTAL_SITES),
                    "currentSite", currentSite))).build();
        } catch (Exception e) {
        	// Unknown error, so we report it as a 500
            response = ExceptionMapperUtil.createResponse(e, Response.Status.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    @GET
    @JSONP
    @NoCache
    @Produces({MediaType.APPLICATION_JSON, "application/javascript"})
    public final Response sites(
                                @Context final HttpServletRequest req,
                                @QueryParam(PaginationUtil.FILTER)   final String filterParam,
                                @QueryParam(PaginationUtil.ARCHIVED) final boolean showArchived,
                                @QueryParam(PaginationUtil.PAGE) final int page,
                                @QueryParam(PaginationUtil.COUNT) final int count
                                ) {

        Response response = null;
        final InitDataObject initData = this.webResource.init(null, true, req, true, null);
        final User user = initData.getUser();
        final String filter;
        final Map<String, Object> paginatedSites;

        try {

            Locale locale = LocaleUtil.getLocale(user, req);

            filter = (null != filterParam && filterParam.endsWith(NO_FILTER))?
                    filterParam.substring(0, filterParam.length() - 1):
                    (null != filterParam)? filterParam: StringUtils.EMPTY;

                    paginatedSites = siteHelper.getPaginatedOrderedSites(showArchived, user, filter, page, count, Boolean.FALSE);
        			
        			response = Response.ok(new ResponseEntityView
                    (map(   "sites",         paginatedSites
                            //,"hostManagerUrl", getHostManagerUrl(req, this.layoutAPI.loadLayoutsForUser(user)) // NOTE: this is not needed yet.
                            ),
                     this.i18NUtil.getMessagesMap(locale, "select-host",
                         "select-host-nice-message", "Invalid-option-selected",
                         "manage-hosts", "cancel", "Change-Host"))
                    ).build(); // 200
        } catch (Exception e) { // this is an unknown error, so we report as a 500.

            response = ExceptionMapperUtil.createResponse(e, Response.Status.INTERNAL_SERVER_ERROR);
        }

        return response;
    } // sites.


    @PUT
    @Path ("/switch/{id}")
    @JSONP
    @NoCache
    @Produces({MediaType.APPLICATION_JSON, "application/javascript"})
    public final Response switchSite(
            @Context final HttpServletRequest req,
            @PathParam("id")   final String hostId
    ) {

        Response response = null;
        final InitDataObject initData = this.webResource.init(null, true, req, true, null); // should logged in
        final HttpSession session = req.getSession();
        final User user = initData.getUser();
        boolean switchDone = false;
        Host hostFound = null;
        
        try {

            if (UtilMethods.isSet(hostId)) {

                // we verified if the host id pass by parameter is one of the user's hosts
                hostFound = siteHelper.getSite( user, hostId);

                if (hostFound != null) {

                    session.setAttribute(
                            com.dotmarketing.util.WebKeys.CMS_SELECTED_HOST_ID, hostId);
                    session.removeAttribute(WebKeys.CONTENTLET_LAST_SEARCH);

                    switchDone = true;
                }
            }

            response = (switchDone) ?
                    Response.ok(new ResponseEntityView(map("hostSwitched",
                            switchDone))).build(): // 200
                    Response.status(Response.Status.NOT_FOUND).build();

        } catch (Exception e) { // this is an unknown error, so we report as a 500.

            response = ExceptionMapperUtil.createResponse(e,
                    Response.Status.INTERNAL_SERVER_ERROR);
        }

        return response;
    } // sites.
    
} // E:O:F:SiteBrowserResource.
