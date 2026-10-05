CREATE TABLE smartmonitoring.smartdataporter_datajobs (
	id bigserial NOT NULL,
	"name" varchar NULL,
	"desc" varchar NULL,
	"action" varchar NULL,
	active bool NULL,
	"start" timestamp NULL,
	stop timestamp NULL,
	repeatsecs int4 NULL,
	CONSTRAINT smartdataporter_datajobs_pk PRIMARY KEY (id)
);

CREATE TABLE smartmonitoring.smartdataporter_datajobs_params (
	id bigserial NOT NULL,
	"key" varchar NOT NULL,
	value varchar NOT NULL,
	datajob_id int8 NOT NULL,
	"type" varchar NOT NULL,
	CONSTRAINT smartdataporter_datajobs_params_pk PRIMARY KEY (id)
);

ALTER TABLE smartmonitoring.smartdataporter_datajobs_params ADD CONSTRAINT smartdataporter_datajobs_params_datajobs_fk FOREIGN KEY (datajob_id) REFERENCES smartmonitoring.smartdataporter_datajobs(id) ON DELETE CASCADE ON UPDATE CASCADE;

CREATE TABLE smartmonitoring.smartdataporter_datajobs_log (
	id bigserial NOT NULL,
	datajob_id int8 NOT NULL,
	"result" text NULL,
	ts timestamptz DEFAULT now() NOT NULL,
	iserror bool NULL,
	processed bool DEFAULT false NOT NULL,
	wastimeout bool DEFAULT false NOT NULL,
	CONSTRAINT smartdataporter_datajobs_log_pk PRIMARY KEY (id)
);

ALTER TABLE smartmonitoring.smartdataporter_datajobs_log ADD CONSTRAINT smartdataporter_datajobs_log_datajobs_fk FOREIGN KEY (datajob_id) REFERENCES smartmonitoring.smartdataporter_datajobs(id) ON DELETE RESTRICT ON UPDATE CASCADE;

CREATE TABLE smartmonitoring.smartdataporter_datajobs_chain (
	id bigserial NOT NULL,
	pre_job_id int8 NOT NULL,
	post_job_id int8 NOT NULL,
	checkkey varchar NULL,
	checkvalue varchar NULL,
	checkchanged varchar NULL,
	active bool DEFAULT true NOT NULL,
	CONSTRAINT smartdataporter_datajobs_chain_pk PRIMARY KEY (id)
);

ALTER TABLE smartmonitoring.smartdataporter_datajobs_chain ADD CONSTRAINT smartdataporter_datajobs_chain_datajobs_postjob_fk FOREIGN KEY (post_job_id) REFERENCES smartmonitoring.smartdataporter_datajobs(id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE smartmonitoring.smartdataporter_datajobs_chain ADD CONSTRAINT smartdataporter_datajobs_chain_datajobs_prejob_fk FOREIGN KEY (pre_job_id) REFERENCES smartmonitoring.smartdataporter_datajobs(id) ON DELETE CASCADE ON UPDATE CASCADE;