package com.github.mustfun.generator.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.mustfun.generator.model.constants.FileConstants;
import com.github.mustfun.generator.model.po.*;
import com.github.mustfun.generator.service.DbSourceService;
import com.github.mustfun.generator.service.ExtApiService;
import com.github.mustfun.generator.service.TemplateService;
import com.github.mustfun.generator.support.handler.ConnectionHolder;
import com.github.mustfun.generator.support.result.BaseResult;
import com.github.mustfun.generator.support.util.DbUtil;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipOutputStream;

/**
 * @author dengzhiyuan
 * @version 1.0
 * @date 2018/4/13
 * @since 1.0
 */
@Service
public class ExtApiServiceImpl implements ExtApiService {

    private static final Logger LOG = LoggerFactory.getLogger(ExtApiServiceImpl.class);

    @Autowired
    private DbSourceService dbService;

    @Autowired
    private TemplateService templateService;


    @Override
    public BaseResult<Long> saveDbConfig(DbSourcePo configPo) {
        BaseResult<Long> baseResult = new BaseResult<>();
        DbUtil dbUtil = new DbUtil(configPo.getDbAddress(), configPo.getDbName(), configPo.getUserName(), configPo.getPassword());
        Connection connection = dbUtil.getConnection();
        if (connection==null){
            baseResult.setStatus(-2);
            baseResult.setMessage("数据库连接失败");
            return baseResult;
        }
        ConnectionHolder.addConnection(configPo.getDbName(),connection);
        //保存到文件中
        //saveToLocalFile(configPo);
        //保存到sqllite中
        dbService.saveDbConfig(configPo);
        //保存sqllite成功
        baseResult.setStatus(1);
        baseResult.setMessage("数据库添加成功");
        return baseResult;
    }

    /**
     * 保存到文件中
     * @param configPo
     */
    private void saveToLocalFile(DbConfigPo configPo) {
        try {
            String s = JSON.toJSONString(configPo);
            List<String> list = new ArrayList<>();
            list.add(s);
            try (OutputStream os = new FileOutputStream(FileConstants.TEMP_DB_CONFIG_DB, true)) {
                IOUtils.writeLines(list, null, os, "UTF-8");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public List<LocalTable> getTables(Connection connection) {
        DatabaseMetaData dbMetData;
        List<LocalTable> localTables = new ArrayList<>();
        try {
            dbMetData = connection.getMetaData();
            String[] types = {"TABLE"};
            ResultSet rs = dbMetData.getTables(null, null, "%", types);
            while (rs.next()) {
                LocalTable localTable = initLocalTable(connection, rs);
                localTables.add(localTable);
            }
        } catch (SQLException e) {
            LOG.info("table出错");
        }
        return localTables;
    }

    private LocalTable initLocalTable(Connection connection, ResultSet rs) throws SQLException {
        LocalTable localTable = new LocalTable();
        String tableName = rs.getString("TABLE_NAME");
        LOG.info(tableName);
        String tableType = rs.getString("TABLE_TYPE");
        String remarks = rs.getString("REMARKS");
        localTable.setComment(remarks);
        localTable.setTableType(tableType);
        localTable.setTableName(tableName);
        getColumns(connection.getMetaData(),tableName,localTable);
        return localTable;
    }

    /**
     * 创建连接
     * @param configPo
     */
    @Override
    public void initDb(DbSourcePo configPo) {
        if (ConnectionHolder.getConnection(configPo.getDbAddress()+configPo.getDbName())!=null){
            return ;
        }
        DbUtil dbUtil = new DbUtil(configPo.getDbAddress(), configPo.getDbName(), configPo.getUserName(), configPo.getPassword());
        Connection connection = dbUtil.getConnection();
        if (connection==null){
            return ;
        }
        ConnectionHolder.addConnection(configPo.getDbAddress()+configPo.getDbName(),connection);
    }

    @Override
    public byte[] generateCode(List<String> tableNames, String packageName, String address, List<Integer> vmList) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
                Connection connection = ConnectionHolder.getConnection(address);
                for (String s : tableNames) {
                    LOG.info("需要生成代码的表{}",s);
                    LocalTable table = new LocalTable();
                    ResultSet rs = connection.getMetaData().getTables(null,null,s, new String[]{"TABLE","VIEW"});
                    while (rs.next()) {
                        table = initLocalTable(connection, rs);
                    }
                    //生成代码啦，替换模板
                    generateCodeUseTemplate(table,zip,packageName,vmList);
                }
            }
        } catch (Exception e) {
            LOG.error("生成表发生异常, {}",e);
        }
        return outputStream.toByteArray();
    }

    /**
     * 用模板生成代码
     * @param columns
     * @param zip
     * @param vmList
     */
    private void generateCodeUseTemplate(LocalTable columns, ZipOutputStream zip, String packageName, List<Integer> vmList) {
        GenerateCodeService.generatorCode(columns,columns.getColumnList(),zip,packageName,vmList);
    }

    private LocalTable getColumns(DatabaseMetaData meta, String tableName,LocalTable localTable) throws SQLException {
        List<LocalColumn> localColumns = new ArrayList<>();
        ResultSet primaryKeys = meta.getPrimaryKeys(null, null, tableName);
        String pkColumnName = null;
        while (primaryKeys.next()) {
            pkColumnName = primaryKeys.getString("COLUMN_NAME");
        }
        LocalColumn pkColumn = new LocalColumn();
        ResultSet survey = meta.getColumns(null, null, tableName, null);
        while (survey.next()) {
            LocalColumn localColumn = new LocalColumn();
            String columnName = survey.getString("COLUMN_NAME");
            localColumn.setColumnName(columnName);
            String columnType = survey.getString("TYPE_NAME");
            localColumn.setDataType(columnType);
            int size = survey.getInt("COLUMN_SIZE");
            localColumn.setSize(size);
            int nullable = survey.getInt("NULLABLE");
            if (nullable == DatabaseMetaData.columnNullable) {
                localColumn.setNullable(true);
            } else {
                localColumn.setNullable(false);
            }
            int position = survey.getInt("ORDINAL_POSITION");
            localColumn.setPosition(position);
            //localColumn.setAttrName(survey.getString("ATTR_NAME"));
            localColumn.setColumnComment(survey.getString("REMARKS"));
            if (columnName.equalsIgnoreCase(pkColumnName)){
                pkColumn=localColumn;
            }
            localColumns.add(localColumn);
        }
        localTable.setPk(pkColumn);
        localTable.setTableName(tableName);
        localTable.setColumnList(localColumns);
        return localTable;
    }


    @Override
    public BaseResult<Long> saveTemplate(Template dbSourcePo) {
        dbSourcePo.setCreateTime(new Date());
        Boolean aBoolean = templateService.saveTemplate(dbSourcePo);
        return new BaseResult<>();
    }

    @Override
    public BaseResult<Boolean> deleteTemplate(Integer id) {
        Boolean aBoolean = templateService.deleteTemplate(id);
        BaseResult<Boolean> objectBaseResult = new BaseResult<>();
        objectBaseResult.setData(aBoolean);
        return objectBaseResult;
    }

    @Override
    public BaseResult<Boolean> updateTemplate(Template template) {
        Boolean aBoolean = templateService.updateTemplate(template);
        BaseResult<Boolean> objectBaseResult = new BaseResult<>();
        objectBaseResult.setData(aBoolean);
        return objectBaseResult;
    }


}
