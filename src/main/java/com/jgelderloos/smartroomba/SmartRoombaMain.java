/*
 *  SmartRoomba - SmartRoombaMain
 *
 *  Copyright (c) 2018 Jon Gelderloos
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General
 *  Public License along with this library; if not, write to the
 *  Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA  02111-1307  USA
 *
 */

package com.jgelderloos.smartroomba;

import com.jgelderloos.smartroomba.roombacomm.RoombaComm;
import com.jgelderloos.smartroomba.roombacomm.RoombaCommPlaybackMode;
import com.jgelderloos.smartroomba.roombacomm.RoombaCommSerial;
import com.jgelderloos.smartroomba.utilities.DataCSV;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SmartRoombaMain {

    public static void main(String[] args) {
        Options options = new Options();

        Option comportOption = new Option("c", "comport", true, "name of com port to connect to");
        comportOption.setRequired(true);
        options.addOption(comportOption);

        Option pauseOption = new Option("p", "pause", true, "number of milliseconds to pause between sensor readings");
        pauseOption.setOptionalArg(true);
        options.addOption(pauseOption);

        Option debugOption = new Option("d", "debug", false, "enable debug output");
        debugOption.setOptionalArg(true);
        options.addOption(debugOption);

        Option hwhandshakeOption = new Option("h", "hwhandshake", false, "use hardware handshaking for Windows bluetooth");
        hwhandshakeOption.setOptionalArg(true);
        options.addOption(hwhandshakeOption);

        Option recordOption = new Option("r", "record", true, "record sensor data");
        recordOption.setOptionalArg(true);
        options.addOption(recordOption);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("SmartRoomba", options);

            System.exit(1);
        }

        if (cmd != null) {
            String comport = cmd.getOptionValue("comport");
            String pause = cmd.getOptionValue("pause", "500");
            boolean debug = cmd.hasOption("debug");
            boolean hwhandshake = cmd.hasOption("hwhandshake");
            String record = cmd.getOptionValue("record", null);

            int pauseTime = 500;
            try {
                pauseTime = Integer.parseInt(pause);
            } catch (NumberFormatException e) {
                System.out.println("pause must be an integer value. See usage for details");
            }

            FileWriter fileWriter = null;
            try {
                if (record != null) {
                    fileWriter = new FileWriter(record);
                }
            } catch (IOException e) {
                System.out.println("Error, could not open the file for writing. " + record);
            }
            DataCSV dataCSV = new DataCSV(fileWriter);
            RoombaComm roombaComm;
            try {
                Integer.parseInt(comport);
                roombaComm = new RoombaCommSerial();
            } catch (NumberFormatException ex) {
                roombaComm = new RoombaCommPlaybackMode();
            }

            SmartRoomba smartRoomba = new SmartRoomba(roombaComm, comport, pauseTime, debug, hwhandshake, dataCSV, new ConcurrentLinkedQueue<>());
            Thread smartRoombaThread = new Thread(smartRoomba);
            smartRoombaThread.start();
        }
    }
}
