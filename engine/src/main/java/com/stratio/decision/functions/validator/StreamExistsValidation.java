/**
 * Copyright (C) 2014 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.decision.functions.validator;

import com.stratio.decision.commons.constants.ReplyCode;
import com.stratio.decision.commons.messages.StratioStreamingMessage;
import com.stratio.decision.exception.StreamExistsException;
import com.stratio.decision.service.StreamOperationService;

public class StreamExistsValidation extends BaseSiddhiRequestValidation {

    private final static String STREAM_ALREADY_EXISTS_MESSAGE = "Stream %s already exists";

    public StreamExistsValidation(StreamOperationService streamOperationService) {
        super(streamOperationService);
    }

    public StreamExistsValidation() {
        super();
    }

    @Override
    public void validate(StratioStreamingMessage request) throws StreamExistsException {
        if (getStreamOperationService().streamExist(request.getStreamName())) {
            throw new StreamExistsException(ReplyCode.KO_STREAM_ALREADY_EXISTS.getCode(), String.format(
                    STREAM_ALREADY_EXISTS_MESSAGE, request.getStreamName()));
        }
    }
}
