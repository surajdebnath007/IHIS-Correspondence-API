package com.ihis.service;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;

import com.ihis.entities.CitizenApplicationEntity;
import com.ihis.entities.CoTriggersEntity;
import com.ihis.entities.EligibilityDetailsEntity;
import com.ihis.repo.CitizenApplicationRepository;
import com.ihis.repo.CoTriggersRepository;
import com.ihis.repo.EligibilityDetailsRepository;
import com.ihis.utils.EmailUtils;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

public class CoServiceImpl implements CoService {

	private static final String PDF_PATH = "D:\\IHIS-PDFs\\";

	@Autowired
	private CoTriggersRepository triggerRepo;

	@Autowired
	private EligibilityDetailsRepository eligRepo;

	@Autowired
	private CitizenApplicationRepository appRepo;

	@Autowired
	private EmailUtils emailUtils;

	@Override
	public Map<String, Integer> generateNotices() {

		// Reading pending triggers from CO_TRIGGERS TABLE
		List<CoTriggersEntity> pendingTriggers = triggerRepo.findByTrgStatus('P');
		int triggerCount = (int) triggerRepo.count();
		int successCount = 0;
		int failureCount = 0;

		Map<String, Integer> map = new HashMap<>();

		ExecutorService exService = Executors.newFixedThreadPool(10);
		ExecutorCompletionService<Object> pool = new ExecutorCompletionService<>(exService);

		for (CoTriggersEntity coTrigger : pendingTriggers) {

			pool.submit(new Callable<Object>() {
				@Override
				public Object call() throws Exception {
					try {
						processTriggers(coTrigger);
					} catch (Exception e) {
						e.printStackTrace();
					}
					return null;
				}
			});
		}

		map.put("No. of Triggers", triggerCount);
		map.put("Success Count", successCount);
		map.put("Failure Count", failureCount);
		return map;
	}

	private void processTriggers(CoTriggersEntity entity) {
		int caseNo = entity.getCaseNo();

		// generate PDF
		EligibilityDetailsEntity eligDtlsEntity = eligRepo.findByCaseNo(caseNo);
		CitizenApplicationEntity appEntity = appRepo.findByCaseNo(caseNo);
		try {
			generatePDF(eligDtlsEntity, appEntity);
		} catch (FileNotFoundException e) {

			e.printStackTrace();
		}

		// send email
		boolean flag = sendMail(appEntity);

		// store PDF in database
		byte[] fileData = new byte[1024];
		FileInputStream fis;
		try {
			fis = new FileInputStream(new File(PDF_PATH + caseNo + ".pdf"));
			fis.read(fileData);
		} catch (IOException e) {

			e.printStackTrace();
		}

		if (flag) {
			entity.setTrgStatus('C');
			triggerRepo.save(entity);
		}

	}

	private boolean sendMail(CitizenApplicationEntity appEntity) {
		boolean flag = false;
		String fileName = "NoticeEmail-Template.txt";
		String to = appEntity.getEmailId();
		String subject = "Application Eligibility Notice - IHIS";
		String body = readMailBody(fileName, appEntity);

		boolean isSent = emailUtils.sendEmail(to, subject, body, new File(PDF_PATH + appEntity.getCaseNo() + ".pdf"));
		if (isSent) {
			flag = true;
		}
		return flag;
	}

	private String readMailBody(String fileName, CitizenApplicationEntity appEntity) {

		String mailBody = null;
		try {
			StringBuffer buffer = new StringBuffer();
			FileReader fileReader = new FileReader(fileName);
			BufferedReader br = new BufferedReader(fileReader);
			String line = br.readLine();

			while (line != null) {
				buffer.append(line);
				line = br.readLine();
			}
			mailBody = buffer.toString();
			mailBody = mailBody.replace("{FNAME}", appEntity.getFullName());

			br.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return mailBody;
	}

	private void generatePDF(EligibilityDetailsEntity eligDtlsEntity, CitizenApplicationEntity appEntity)
			throws FileNotFoundException {

		Document document = new Document();

		FileOutputStream fileOut = new FileOutputStream(new File(PDF_PATH + eligDtlsEntity.getCaseNo() + ".pdf"));

		PdfWriter writer = PdfWriter.getInstance(document, fileOut);

		document.open();

		Font font = new Font(Font.HELVETICA, 16, Font.BOLDITALIC, Color.RED);
		Paragraph para = new Paragraph("Eligibility Details", font);
		document.add(para);

		PdfPTable table = new PdfPTable(2);

		table.addCell("HolderName");
		table.addCell(appEntity.getFullName());

		table.addCell("Holder SSN");
		table.addCell(String.valueOf(appEntity.getSsn()));

		table.addCell("Plan Status");
		table.addCell(eligDtlsEntity.getPlanStatus());

		table.addCell("Plan Start Date");
		table.addCell(String.valueOf(eligDtlsEntity.getStartDate()));

		table.addCell("Plan End Date");
		table.addCell(String.valueOf(eligDtlsEntity.getEndDate()));

		table.addCell("Benefit Amount");
		table.addCell(String.valueOf(eligDtlsEntity.getBenefitAmount()));

		table.addCell("Denial Reason");
		table.addCell(eligDtlsEntity.getDenialReason());

		document.add(table);
		document.close();
		writer.close();
	}

}
