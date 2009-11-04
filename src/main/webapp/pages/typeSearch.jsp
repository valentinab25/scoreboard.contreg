<%@page contentType="text/html;charset=UTF-8"%>

<%@ include file="/pages/common/taglibs.jsp"%>	

<stripes:layout-render name="/pages/common/template.jsp" pageTitle="Type search">
	
	<stripes:layout-component name="contents">
		<c:if test="${not empty actionBean.resultList}">
			<div id="operations" class="export_div" style="display:none;">
				<ul>
					<li><a href="#">Export</a></li>
				</ul>
			</div>
		</c:if>
		<div style="max-width: 750px;" id="export_form_container">
       <h1>Type search</h1>
       <p>
       	This page enables to find content by type. Below is the list of types known to CR.
       	This does not mean that CR has content for every listed type.
       	The search will return the list of all resources having the type you selected.
       	To view a resource's factsheet, click the relevant action icon next to it.   
       </p>
		<crfn:form action="/typeSearch.action" method="get">
			<stripes:select name="type">
				<c:forEach items="${actionBean.availableTypes }" var="type">
					<stripes:option value="${type.id}">${type.value} (${type.id})</stripes:option>
				</c:forEach>
			</stripes:select>
			<stripes:submit name="search" value="Search" /> 
			<c:if test='${sessionScope.crUser!=null && crfn:hasPermission(sessionScope.crUser.userName, "/", "u")}'>
				&nbsp;<stripes:submit name="introspect" value="Introspect"/>
			</c:if>		
		</crfn:form><br/>
			<c:if test="${not empty actionBean.type}">
			<div id="export_form_noscript">
				<fieldset>
				<legend>Export options</legend>
				<crfn:form action="/typeSearch.action" method="post">
					<stripes:hidden name="type" value="${actionBean.type }"/>
					<label for="export_resource">Resource identifier</label>
					<stripes:select id="export_resource" name="uriResourceIdentifier">
						<stripes:option value="true">Uri</stripes:option>
						<stripes:option value="false">Label</stripes:option>
					</stripes:select>
					&nbsp;
					<label for="export_format">Export format</label>
					<stripes:select id="export_format" name="exportFormat">
						<stripes:option value=".xls">.xls</stripes:option>
					</stripes:select> <br/>
					<label for="export_columns">Select columns to be exported</label><br/>
					<stripes:select name="exportColumns" multiple="multiple" size="5" style="min-width:250px; width:250px;">
						<c:forEach items="${actionBean.availableColumns }" var="column">
							<stripes:option value="${column.key}">${column.value}</stripes:option>					
						</c:forEach>
					</stripes:select> 
					<stripes:submit name="export" value="Export" id="export_form_submit" />
				</crfn:form>
				</fieldset>
			</div>
			<div style="margin-bottom:10px; float:right;  min-width:400px; width:50%;">
				<fieldset>
				<legend>Select filters</legend>
				<c:if test="${not empty actionBean.availableFilters}">
				<crfn:form action="/typeSearch.action" method="post"> 
					<stripes:hidden name="addFilter" value="addFilter"/>
					<stripes:hidden name="type" value="${actionBean.type }"/>
					<stripes:select name="newFilter" onchange="this.form.submit();">
						<stripes:option value="">Select filter to add </stripes:option>
						<c:forEach items="${actionBean.availableFilters}" var="column">
							<stripes:option value="${column.key}">${column.value}</stripes:option>					
						</c:forEach>
					</stripes:select>
					<noscript>
						<stripes:submit name="addFilter" value="Add filter"/>
					</noscript>
				</crfn:form>
				<br/>
				</c:if>
					<c:if test="${not empty actionBean.displayFilters}">
						<crfn:form action="/typeSearch.action" method="post">
						<stripes:hidden name="type" value="${actionBean.type }"/>
						<table>
							<tbody>
								<c:forEach items="${actionBean.displayFilters}" var="filter">
									<tr>
										<td>${filter.key}</td>
										<td><stripes:text name="selectedFilters[${filter.value.id}]" size="40" value="${filter.value.value}"/></td>
										<td>
											<stripes:submit name="removeFilter_${filter.value.id}" value="-"/>
										</td>
									</tr>
								</c:forEach>
							</tbody>
						</table>
						<stripes:submit name="applyFilters" value="Apply filters"/>
						</crfn:form>
						<br/>
					</c:if>	
				</fieldset>		
			</div>
			</c:if>
		<c:if test="${not empty actionBean.type and not empty actionBean.availableColumns}">
			<div style="max-width: 350px;">
				<fieldset>
				<legend>Select columns to be displayed (max <c:out value="${actionBean.maxDisplayedColumns}"/>)</legend>
				<crfn:form action="/typeSearch.action" method="post">
					<stripes:hidden name="type" value="${actionBean.type }"/>
					<stripes:select name="selectedColumns" multiple="multiple" size="5" style="min-width:250px; width:250px;">
						<c:forEach items="${actionBean.availableColumns }" var="column">
							<stripes:option value="${column.key}">${column.value}</stripes:option>					
						</c:forEach>
					</stripes:select>			
					<stripes:submit name="setSearchColumns" value="Set"/>
				</crfn:form>
				</fieldset>		
			</div>
		</c:if>
		<c:if test="${! empty actionBean.type}">
			<stripes:layout-render name="/pages/common/subjectsResultList.jsp" tableClass="sortable" />
		</c:if>
		</div>
	</stripes:layout-component>
</stripes:layout-render>
