package edu.utah.seq.vcf.json;

import org.json.JSONException;
import org.json.JSONObject;

import util.gen.Json;

public class TempusReport {

	private String reportId = null;
	private String signout_date = null;
	private String bioInfPipeline = null;
	private String notes = null;
	
	/*
    "report": {
        "reportId": "03cfd69c-5ea0-4ba6-92f6-bc43175c1222",
        "workflow": {
            "reportStatus": "standard",
            "details": null,
            "reportType": "DNA"
        },
        "signing_pathologist": "Timothy Taxter, M.D.",
        "signout_date": "2018-10-12T02:50:44+00:00",
        "bioInfPipeline": "1.3.5",
        "notes": "The tumor shows a loss of heterozygosity in TP53.RNA analysis is being performed and will be reported in the Tempus online portal when complete."
     },

	 */
	public TempusReport(JSONObject object, TempusJson2Vcf tempusJson2Vcf) throws JSONException {
		JSONObject report = object.getJSONObject("report");
		reportId = Json.getStringAttribute(report, "reportId");
		signout_date = Json.getStringAttribute(report, "signout_date");
		bioInfPipeline = Json.getStringAttribute(report, "bioInfPipeline");
		notes = Json.getStringAttribute(report, "notes");
		TempusJson2Vcf.add(bioInfPipeline, tempusJson2Vcf.bioInfPipelines);
	}

	public String getReportId() {
		return reportId;
	}

	public String getSignout_date() {
		return signout_date;
	}

	public String getBioInfPipeline() {
		return bioInfPipeline;
	}

	public String getNotes() {
		return notes;
	}
}
